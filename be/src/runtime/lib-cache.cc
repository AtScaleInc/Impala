// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "runtime/lib-cache.h"

#include <boost/filesystem.hpp>
#include <boost/foreach.hpp>
#include <boost/thread/locks.hpp>

#include "codegen/llvm-codegen.h"
#include "runtime/hdfs-fs-cache.h"
#include "runtime/runtime-state.h"
#include "util/dynamic-util.h"
#include "util/hash-util.h"
#include "util/hdfs-util.h"
#include "util/path-builder.h"
#include "util/test-info.h"

using namespace boost;
using namespace std;
using namespace impala;

DEFINE_string(local_library_dir, "/tmp",
              "Local directory to copy UDF libraries from HDFS into");

scoped_ptr<LibCache> LibCache::instance_;

struct LibCache::LibCacheEntry {
  // Lock protecting all fields in this entry
  boost::mutex lock;

  // The number of users that are using this cache entry. If this is
  // a .so, we can't dlclose unless the use_count goes to 0.
  int use_count;

  // If true, this cache entry should be removed from lib_cache_ when
  // the use_count goes to 0.
  bool should_remove;

  // If true, we need to check if there is a newer version of the cached library in HDFS
  // on next access.
  bool check_needs_refresh;

  // The type of this file.
  LibType type;

  // The path on the local file system for this library.
  std::string local_path;

  // Status returned from copying this file from HDFS.
  Status copy_file_status;

  // The last modification time of the HDFS file in seconds.
  time_t last_mod_time;

  // Handle from dlopen.
  void* shared_object_handle;

  // mapping from symbol => address of loaded symbol.
  // Only used if the type is TYPE_SO.
  typedef boost::unordered_map<std::string, void*> SymbolMap;
  SymbolMap symbol_cache;

  // Set of symbols in this entry. This is populated once on load and read
  // only. This is only used if it is a llvm module.
  // TODO: it would be nice to be able to do this for .so's as well but it's
  // not trivial to walk an .so for the symbol table.
  boost::unordered_set<std::string> symbols;

  LibCacheEntry() : use_count(0), should_remove(false), check_needs_refresh(false),
                    shared_object_handle(NULL) {}
  ~LibCacheEntry();
};

LibCache::LibCache() : current_process_handle_(NULL) {
}

LibCache::~LibCache() {
  DropCache();
  if (current_process_handle_ != NULL) DynamicClose(current_process_handle_);
}

Status LibCache::Init() {
  DCHECK(LibCache::instance_.get() == NULL);
  LibCache::instance_.reset(new LibCache());
  return LibCache::instance_->InitInternal();
}

Status LibCache::InitInternal() {
  if (TestInfo::is_fe_test()) {
    // In the FE tests, NULL gives the handle to the java process.
    // Explicitly load the fe-support shared object.
    string fe_support_path;
    PathBuilder::GetFullBuildPath("service/libfesupport.so", &fe_support_path);
    RETURN_IF_ERROR(DynamicOpen(fe_support_path.c_str(), &current_process_handle_));
  } else {
    RETURN_IF_ERROR(DynamicOpen(NULL, &current_process_handle_));
  }
  DCHECK(current_process_handle_ != NULL)
      << "We should always be able to get current process handle.";
  return Status::OK;
}

LibCache::LibCacheEntry::~LibCacheEntry() {
  if (shared_object_handle != NULL) {
    DCHECK_EQ(use_count, 0);
    DCHECK(should_remove);
    DynamicClose(shared_object_handle);
  }
  unlink(local_path.c_str());
}

Status LibCache::GetSoFunctionPtr(const string& hdfs_lib_file, const string& symbol,
                                  void** fn_ptr, LibCacheEntry** ent) {
  if (hdfs_lib_file.empty()) {
    // Just loading a function ptr in the current process. No need to take any locks.
    DCHECK(current_process_handle_ != NULL);
    RETURN_IF_ERROR(DynamicLookup(current_process_handle_, symbol.c_str(), fn_ptr));
    return Status::OK;
  }

  LibCacheEntry* entry = NULL;
  unique_lock<mutex> lock;
  if (ent != NULL && *ent != NULL) {
    // Reuse already-cached entry provided by user
    entry = *ent;
    unique_lock<mutex> l(entry->lock);
    lock.swap(l);
  } else {
    RETURN_IF_ERROR(GetCacheEntry(hdfs_lib_file, TYPE_SO, &lock, &entry));
  }
  DCHECK(entry != NULL);
  DCHECK_EQ(entry->type, TYPE_SO);

  LibCacheEntry::SymbolMap::iterator it = entry->symbol_cache.find(symbol);
  if (it != entry->symbol_cache.end()) {
    *fn_ptr = it->second;
  } else {
    RETURN_IF_ERROR(DynamicLookup(entry->shared_object_handle, symbol.c_str(), fn_ptr));
    entry->symbol_cache[symbol] = *fn_ptr;
  }

  DCHECK(*fn_ptr != NULL);
  if (ent != NULL && *ent == NULL) {
    // Only set and increment user's entry if it wasn't already cached
    *ent = entry;
    ++(*ent)->use_count;
  }
  return Status::OK;
}

void LibCache::DecrementUseCount(LibCacheEntry* entry) {
  if (entry == NULL) return;
  bool can_delete = false;
  {
    unique_lock<mutex> lock(entry->lock);;
    --entry->use_count;
    can_delete = (entry->use_count == 0 && entry->should_remove);
  }
  if (can_delete) delete entry;
}

Status LibCache::GetLocalLibPath(const string& hdfs_lib_file, LibType type,
                                 string* local_path) {
  unique_lock<mutex> lock;
  LibCacheEntry* entry = NULL;
  RETURN_IF_ERROR(GetCacheEntry(hdfs_lib_file, type, &lock, &entry));
  DCHECK(entry != NULL);
  DCHECK_EQ(entry->type, type);
  *local_path = entry->local_path;
  return Status::OK;
}

Status LibCache::CheckSymbolExists(const string& hdfs_lib_file, LibType type,
                                   const string& symbol) {
  if (type == TYPE_SO) {
    void* dummy_ptr = NULL;
    return GetSoFunctionPtr(hdfs_lib_file, symbol, &dummy_ptr, NULL);
  } else if (type == TYPE_IR) {
    unique_lock<mutex> lock;
    LibCacheEntry* entry = NULL;
    RETURN_IF_ERROR(GetCacheEntry(hdfs_lib_file, type, &lock, &entry));
    DCHECK(entry != NULL);
    DCHECK_EQ(entry->type, TYPE_IR);
    if (entry->symbols.find(symbol) == entry->symbols.end()) {
      stringstream ss;
      ss << "Symbol '" << symbol << "' does not exist in module: " << hdfs_lib_file
         << " (local path: " << entry->local_path << ")";
      return Status(ss.str());
    }
    return Status::OK;
  } else if (type == TYPE_JAR) {
    // TODO: figure out how to inspect contents of jars
    unique_lock<mutex> lock;
    LibCacheEntry* dummy_entry = NULL;
    return GetCacheEntry(hdfs_lib_file, type, &lock, &dummy_entry);
  } else {
    DCHECK(false);
    return Status("Shouldn't get here.");
  }
}

void LibCache::SetNeedsRefresh(const string& hdfs_lib_file) {
  unique_lock<mutex> lib_cache_lock(lock_);
  LibMap::iterator it = lib_cache_.find(hdfs_lib_file);
  if (it == lib_cache_.end()) return;
  LibCacheEntry* entry = it->second;
  lib_cache_lock.unlock();

  unique_lock<mutex> entry_lock(entry->lock);
  entry->check_needs_refresh = true;
}

void LibCache::RemoveEntry(const string& hdfs_lib_file) {
  unique_lock<mutex> lib_cache_lock(lock_);
  LibMap::iterator it = lib_cache_.find(hdfs_lib_file);
  if (it == lib_cache_.end()) return;
  RemoveEntryInternal(hdfs_lib_file, it);
}

void LibCache::RemoveEntryInternal(const string& hdfs_lib_file,
                                   const LibMap::iterator& entry_iter) {
  LibCacheEntry* entry = entry_iter->second;
  VLOG(1) << "Removing lib cache entry: " << hdfs_lib_file
          << ", local path: " << entry->local_path;
  unique_lock<mutex> entry_lock(entry->lock);

  // We have both locks so no other thread can be updating lib_cache_ or trying to get
  // the entry.
  lib_cache_.erase(entry_iter);

  entry->should_remove = true;
  DCHECK_GE(entry->use_count, 0);
  bool can_delete = entry->use_count == 0;

  // Now that the entry is removed from the map, it means no future threads
  // can find it->second (the entry), so it is safe to unlock.
  entry_lock.unlock();

  // Now that we've unlocked, we can delete this entry if no one is using it.
  if (can_delete) delete entry;
}

void LibCache::DropCache() {
  unique_lock<mutex> lib_cache_lock(lock_);
  BOOST_FOREACH(LibMap::value_type& v, lib_cache_) {
    bool can_delete = false;
    {
      // Lock to wait for any threads currently processing the entry.
      unique_lock<mutex> entry_lock(v.second->lock);
      v.second->should_remove = true;
      DCHECK_GE(v.second->use_count, 0);
      can_delete = v.second->use_count == 0;
    }
    VLOG(1) << "Removed lib cache entry: " << v.first;
    if (can_delete) delete v.second;
  }
  lib_cache_.clear();
}

Status LibCache::GetCacheEntry(const string& hdfs_lib_file, LibType type,
                               unique_lock<mutex>* entry_lock, LibCacheEntry** entry) {
  DCHECK(!hdfs_lib_file.empty());
  *entry = NULL;

  // Check if this file is already cached.
  unique_lock<mutex> lib_cache_lock(lock_);
  LibMap::iterator it = lib_cache_.find(hdfs_lib_file);
  if (it != lib_cache_.end()) {
    *entry = it->second;
    if ((*entry)->check_needs_refresh) {
      // Check if file has been modified since loading the cached copy. If so, remove the
      // cached entry and create a new one.
      (*entry)->check_needs_refresh = false;
      time_t last_mod_time;
      hdfsFS hdfs_conn = HdfsFsCache::instance()->GetDefaultConnection();
      RETURN_IF_ERROR(
          GetLastModificationTime(hdfs_conn, hdfs_lib_file.c_str(), &last_mod_time));
      if ((*entry)->last_mod_time < last_mod_time) {
        RemoveEntryInternal(hdfs_lib_file, it);
        *entry = NULL;
      }
    }
  }

  if (*entry != NULL) {
    // Release the lib_cache_ lock. This guarantees other threads looking at other
    // libs can continue.
    lib_cache_lock.unlock();
    unique_lock<mutex> local_entry_lock((*entry)->lock);
    entry_lock->swap(local_entry_lock);

    RETURN_IF_ERROR((*entry)->copy_file_status);
    DCHECK_EQ((*entry)->type, type);
    DCHECK(!(*entry)->local_path.empty());
    return Status::OK;
  }

  // Entry didn't exist. Add the entry then release lock_ (so other libraries
  // can be accessed).
  *entry = new LibCacheEntry();

  // Grab the entry lock before adding it to lib_cache_. We still need to do more
  // work to initialize *entry and we don't want another thread to pick up
  // the uninitialized entry.
  unique_lock<mutex> local_entry_lock((*entry)->lock);
  entry_lock->swap(local_entry_lock);
  lib_cache_[hdfs_lib_file] = *entry;
  lib_cache_lock.unlock();

  // At this point we have the entry lock but not the lib cache lock.
  DCHECK(*entry != NULL);
  (*entry)->type = type;

  // Copy the file
  (*entry)->local_path = MakeLocalPath(hdfs_lib_file, FLAGS_local_library_dir);
  VLOG(1) << "Adding lib cache entry: " << hdfs_lib_file
          << ", local path: " << (*entry)->local_path;

  hdfsFS hdfs_conn = HdfsFsCache::instance()->GetDefaultConnection();
  hdfsFS local_conn = HdfsFsCache::instance()->GetLocalConnection();

  // Note: the file can be updated between getting last_mod_time and copying the file to
  // local_path. This can only result in the file unnecessarily being refreshed, and does
  // not affect correctness.
  (*entry)->copy_file_status = GetLastModificationTime(
      hdfs_conn, hdfs_lib_file.c_str(), &(*entry)->last_mod_time);
  RETURN_IF_ERROR((*entry)->copy_file_status);

  (*entry)->copy_file_status = CopyHdfsFile(
      hdfs_conn, hdfs_lib_file, local_conn, (*entry)->local_path);
  RETURN_IF_ERROR((*entry)->copy_file_status);

  if (type == TYPE_SO) {
    // dlopen the local library
    RETURN_IF_ERROR(
        DynamicOpen((*entry)->local_path.c_str(), &(*entry)->shared_object_handle));
  } else if (type == TYPE_IR) {
    // Load the module and populate all symbols.
    ObjectPool pool;
    scoped_ptr<LlvmCodeGen> codegen;
    RETURN_IF_ERROR(LlvmCodeGen::LoadFromFile(&pool, (*entry)->local_path, &codegen));
    codegen->GetSymbols(&(*entry)->symbols);
  } else {
    DCHECK_EQ(type, TYPE_JAR);
    // Nothing to do.
  }

  return Status::OK;
}

string LibCache::MakeLocalPath(const string& hdfs_path, const string& local_dir) {
  // Append the pid and library number to the local directory.
  filesystem::path src(hdfs_path);
  stringstream dst;
  dst << local_dir << "/" << src.stem().native() << "." << getpid() << "."
      << (num_libs_copied_++) << src.extension().native();
  return dst.str();
}
