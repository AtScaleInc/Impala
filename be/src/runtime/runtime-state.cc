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

#include <string>
#include <sstream>

#include "common/logging.h"
#include <boost/algorithm/string/join.hpp>

#include "codegen/llvm-codegen.h"
#include "common/object-pool.h"
#include "common/status.h"
#include "exprs/expr.h"
#include "runtime/buffered-block-mgr.h"
#include "runtime/descriptors.h"
#include "runtime/runtime-state.h"
#include "runtime/timestamp-value.h"
#include "runtime/data-stream-mgr.h"
#include "runtime/data-stream-recvr.h"
#include "util/bitmap.h"
#include "util/cpu-info.h"
#include "util/debug-util.h"
#include "util/disk-info.h"
#include "util/error-util.h"
#include "util/jni-util.h"
#include "util/mem-info.h"

#include <jni.h>
#include <iostream>

DECLARE_int32(max_errors);

using namespace boost;
using namespace llvm;
using namespace std;
using namespace boost::algorithm;

// The fraction of the query mem limit that is used for the block mgr. Operators
// that accumulate memory all use the block mgr so the majority of the memory should
// be allocated to the block mgr. The remaining memory is used by the non-spilling
// operators and should be independent of data size.
static const float BLOCK_MGR_MEM_FRACTION = 0.8f;

// The minimum amount of memory that must be left after the block mgr reserves the
// BLOCK_MGR_MEM_FRACTION. The block limit is:
// min(query_limit * BLOCK_MGR_MEM_FRACTION, query_limit - BLOCK_MGR_MEM_MIN_REMAINING)
// TODO: this value was picked arbitrarily and the tests are written to rely on this
// for the minimum memory required to run the query. Revisit.
static const int64_t BLOCK_MGR_MEM_MIN_REMAINING = 100 * 1024 * 1024;

namespace impala {

RuntimeState::RuntimeState(const TPlanFragmentInstanceCtx& fragment_instance_ctx,
    const string& cgroup, ExecEnv* exec_env)
  : obj_pool_(new ObjectPool()),
    unreported_error_idx_(0),
    fragment_instance_ctx_(fragment_instance_ctx),
    now_(new TimestampValue(fragment_instance_ctx_.query_ctx.now_string.c_str(),
        fragment_instance_ctx_.query_ctx.now_string.size())),
    cgroup_(cgroup),
    profile_(obj_pool_.get(),
        "Fragment " + PrintId(fragment_instance_ctx_.fragment_instance_id)),
    is_cancelled_(false),
    query_resource_mgr_(NULL),
    root_node_id_(-1) {
  Status status = Init(exec_env);
  DCHECK(status.ok()) << status.GetErrorMsg();
}

RuntimeState::RuntimeState(const TQueryCtx& query_ctx)
  : obj_pool_(new ObjectPool()),
    unreported_error_idx_(0),
    now_(new TimestampValue(query_ctx.now_string.c_str(),
        query_ctx.now_string.size())),
    exec_env_(ExecEnv::GetInstance()),
    profile_(obj_pool_.get(), "<unnamed>"),
    is_cancelled_(false),
    query_resource_mgr_(NULL),
    root_node_id_(-1) {
  fragment_instance_ctx_.__set_query_ctx(query_ctx);
  fragment_instance_ctx_.query_ctx.request.query_options.__set_batch_size(
      DEFAULT_BATCH_SIZE);
}

RuntimeState::~RuntimeState() {
  block_mgr_.reset();
  // query_mem_tracker_ must be valid as long as instance_mem_tracker_ is so
  // delete instance_mem_tracker_ first.
  // LogUsage() walks the MemTracker tree top-down when the memory limit is exceeded.
  // Break the link between the instance_mem_tracker and its parent (query_mem_tracker_)
  // before the instance_mem_tracker_ and its children are destroyed.
  if (instance_mem_tracker_.get() != NULL) {
    // May be NULL if InitMemTrackers() is not called, for example from tests.
    instance_mem_tracker_->UnregisterFromParent();
  }

  instance_mem_tracker_.reset();
  query_mem_tracker_.reset();
}

Status RuntimeState::Init(ExecEnv* exec_env) {
  SCOPED_TIMER(profile_.total_time_counter());
  exec_env_ = exec_env;
  TQueryOptions& query_options =
      fragment_instance_ctx_.query_ctx.request.query_options;
  if (!query_options.disable_codegen) {
    RETURN_IF_ERROR(CreateCodegen());
  } else {
    codegen_.reset(NULL);
  }
  if (query_options.max_errors <= 0) {
    // TODO: fix linker error and uncomment this
    //query_options_.max_errors = FLAGS_max_errors;
    query_options.max_errors = 100;
  }
  if (query_options.batch_size <= 0) {
    query_options.__set_batch_size(DEFAULT_BATCH_SIZE);
  }

  // Register with the thread mgr
  if (exec_env != NULL) {
    resource_pool_ = exec_env->thread_mgr()->RegisterPool();
    DCHECK(resource_pool_ != NULL);
  }

  total_cpu_timer_ = ADD_TIMER(runtime_profile(), "TotalCpuTime");
  total_storage_wait_timer_ = ADD_TIMER(runtime_profile(), "TotalStorageWaitTime");
  total_network_send_timer_ = ADD_TIMER(runtime_profile(), "TotalNetworkSendTime");
  total_network_receive_timer_ = ADD_TIMER(runtime_profile(), "TotalNetworkReceiveTime");

  return Status::OK;
}

void RuntimeState::InitMemTrackers(const TUniqueId& query_id, const string* pool_name,
    int64_t query_bytes_limit, int64_t query_rm_reservation_limit_bytes) {
  MemTracker* query_parent_tracker = exec_env_->process_mem_tracker();
  if (pool_name != NULL) {
    query_parent_tracker = MemTracker::GetRequestPoolMemTracker(*pool_name,
        query_parent_tracker);
  }
  query_mem_tracker_ =
      MemTracker::GetQueryMemTracker(query_id, query_bytes_limit,
          query_rm_reservation_limit_bytes, query_parent_tracker, query_resource_mgr());
  instance_mem_tracker_.reset(new MemTracker(runtime_profile(), -1, -1,
      runtime_profile()->name(), query_mem_tracker_.get()));

  udf_mem_tracker_.reset(
      new MemTracker(-1, -1, "UDFs", instance_mem_tracker_.get()));
}

Status RuntimeState::CreateBlockMgr() {
  DCHECK(block_mgr_.get() == NULL);

  // Compute the max memory the block mgr will use.
  int64_t block_mgr_limit = query_mem_tracker_->SpareCapacity();
  if (block_mgr_limit < 0) block_mgr_limit = numeric_limits<int64_t>::max();
  block_mgr_limit = min(static_cast<int64_t>(block_mgr_limit * BLOCK_MGR_MEM_FRACTION),
      block_mgr_limit - BLOCK_MGR_MEM_MIN_REMAINING);
  if (block_mgr_limit < 0) block_mgr_limit = 0;
  if (query_options().__isset.max_block_mgr_memory &&
      query_options().max_block_mgr_memory > 0) {
    block_mgr_limit = query_options().max_block_mgr_memory;
    LOG(ERROR) << "Block mgr mem limit: "
               << PrettyPrinter::Print(block_mgr_limit, TCounterType::BYTES);
  }

  RETURN_IF_ERROR(BufferedBlockMgr::Create(this, query_mem_tracker(),
        runtime_profile(), block_mgr_limit, io_mgr()->max_read_buffer_size(),
        &block_mgr_));
  return Status::OK;
}

void RuntimeState::set_now(const TimestampValue* now) {
  now_.reset(new TimestampValue(*now));
}

Status RuntimeState::CreateCodegen() {
  if (codegen_.get() != NULL) return Status::OK;
  RETURN_IF_ERROR(LlvmCodeGen::LoadImpalaIR(obj_pool_.get(), &codegen_));
  codegen_->EnableOptimizations(true);
  profile_.AddChild(codegen_->runtime_profile());
  return Status::OK;
}

bool RuntimeState::ErrorLogIsEmpty() {
  lock_guard<mutex> l(error_log_lock_);
  return (error_log_.size() == 0);
}

string RuntimeState::ErrorLog() {
  lock_guard<mutex> l(error_log_lock_);
  return join(error_log_, "\n");
}

string RuntimeState::FileErrors() const {
  lock_guard<mutex> l(file_errors_lock_);
  stringstream out;
  for (int i = 0; i < file_errors_.size(); ++i) {
    out << file_errors_[i].second << " errors in " << file_errors_[i].first << endl;
  }
  return out.str();
}

void RuntimeState::ReportFileErrors(const std::string& file_name, int num_errors) {
  lock_guard<mutex> l(file_errors_lock_);
  file_errors_.push_back(make_pair(file_name, num_errors));
}

bool RuntimeState::LogError(const string& error) {
  lock_guard<mutex> l(error_log_lock_);
  if (error_log_.size() < query_options().max_errors) {
    VLOG_QUERY << "Error from query " << query_id() << ": " << error;
    error_log_.push_back(error);
    return true;
  }
  return false;
}

void RuntimeState::LogError(const Status& status) {
  if (status.ok()) return;
  // Don't log cancelled or mem limit exceeded to the log.
  // For cancelled, he error message is not useful ("Cancelled") and can happen due to
  // a limit clause.
  // For mem limit exceeded, the query will report it via SetMemLimitExceeded which
  // makes the status error message redundant.
  if (status.IsCancelled() || status.IsMemLimitExceeded()) return;
  LogError(status.GetErrorMsg());
}

void RuntimeState::GetUnreportedErrors(vector<string>* new_errors) {
  lock_guard<mutex> l(error_log_lock_);
  if (unreported_error_idx_ < error_log_.size()) {
    new_errors->assign(error_log_.begin() + unreported_error_idx_, error_log_.end());
    unreported_error_idx_ = error_log_.size();
  }
}

Status RuntimeState::SetMemLimitExceeded(MemTracker* tracker,
    int64_t failed_allocation_size) {
  DCHECK_GE(failed_allocation_size, 0);
  {
    lock_guard<mutex> l(query_status_lock_);
    if (query_status_.ok()) {
      query_status_ = Status::MEM_LIMIT_EXCEEDED;
    } else {
      return query_status_;
    }
  }

  DCHECK(query_mem_tracker_.get() != NULL);
  stringstream ss;
  ss << "Memory Limit Exceeded\n";
  if (failed_allocation_size != 0) {
    DCHECK(tracker != NULL);
    ss << "  " << tracker->label() << " could not allocate "
       << PrettyPrinter::Print(failed_allocation_size, TCounterType::BYTES)
       << " without exceeding limit."
       << endl;
  }

  if (exec_env_->process_mem_tracker()->LimitExceeded()) {
    ss << exec_env_->process_mem_tracker()->LogUsage();
  } else {
    ss << query_mem_tracker_->LogUsage();
  }
  LogError(ss.str());
  // Add warning about missing stats.
  if (query_ctx().__isset.tables_missing_stats
      && !query_ctx().tables_missing_stats.empty()) {
    LogError(GetTablesMissingStatsWarning(query_ctx().tables_missing_stats));
  }
  DCHECK(query_status_.IsMemLimitExceeded());
  return query_status_;
}

Status RuntimeState::CheckQueryState() {
  // TODO: it would be nice if this also checked for cancellation, but doing so breaks
  // cases where we use Status::CANCELLED to indicate that the limit was reached.
  if (instance_mem_tracker_->AnyLimitExceeded()) return SetMemLimitExceeded();
  lock_guard<mutex> l(query_status_lock_);
  return query_status_;
}

void RuntimeState::AddBitmapFilter(SlotId slot, const Bitmap* bitmap) {
  lock_guard<mutex> l(bitmap_lock_);
  if (bitmap != NULL) {
    Bitmap* existing_bitmap = NULL;
    if (slot_bitmap_filters_.find(slot) != slot_bitmap_filters_.end()) {
      existing_bitmap = slot_bitmap_filters_[slot];
    } else {
      existing_bitmap = obj_pool_->Add(new Bitmap(slot_filter_bitmap_size()));
      existing_bitmap->SetAllBits(true);
      slot_bitmap_filters_[slot] = existing_bitmap;
    }
    DCHECK(existing_bitmap != NULL);
    existing_bitmap->And(bitmap);
  }
}

}
