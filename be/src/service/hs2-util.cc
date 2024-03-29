// Copyright 2014 Cloudera Inc.
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

#include "service/hs2-util.h"

#include "common/logging.h"
#include "runtime/raw-value.h"
#include "runtime/types.h"

using namespace apache::hive::service::cli;
using namespace impala;
using namespace std;

// Set the null indicator bit for row 'row_idx', assuming this will be called for
// successive increasing values of row_idx. If 'is_null' is true, the row_idx'th bit will
// be set in 'nulls' (taking the LSB as bit 0). If 'is_null' is false, the row_idx'th bit
// will be unchanged. If 'nulls' does not contain 'row_idx' bits, it will be extended by
// one byte.
inline void SetNullBit(int64_t row_idx, bool is_null, string* nulls) {
  DCHECK_LE(row_idx >> 3, nulls->size());
  int16_t mod_8 = row_idx & 0x7;
  if (mod_8 == 0) (*nulls) += '\0';
  (*nulls)[row_idx >> 3] |= (1 << mod_8) * is_null;
}

// For V6 and above
void impala::TColumnValueToHS2TColumn(const TColumnValue& col_val,
    const TColumnType& type, int64_t row_idx, thrift::TColumn* column) {
  string* nulls;
  bool is_null;
  switch (type.types[0].scalar_type.type) {
    case TPrimitiveType::BOOLEAN:
      is_null = col_val.__isset.bool_val;
      column->boolVal.values.push_back(col_val.bool_val);
      nulls = &column->boolVal.nulls;
      break;
    case TPrimitiveType::TINYINT:
      is_null = col_val.__isset.byte_val;
      column->byteVal.values.push_back(col_val.byte_val);
      nulls = &column->byteVal.nulls;
      break;
    case TPrimitiveType::SMALLINT:
      is_null = col_val.__isset.short_val;
      column->i16Val.values.push_back(col_val.short_val);
      nulls = &column->i16Val.nulls;
      break;
    case TPrimitiveType::INT:
      is_null = col_val.__isset.int_val;
      column->i32Val.values.push_back(col_val.int_val);
      nulls = &column->i32Val.nulls;
      break;
    case TPrimitiveType::BIGINT:
      is_null = col_val.__isset.long_val;
      column->i64Val.values.push_back(col_val.long_val);
      nulls = &column->i64Val.nulls;
      break;
    case TPrimitiveType::FLOAT:
    case TPrimitiveType::DOUBLE:
      is_null = col_val.__isset.double_val;
      column->doubleVal.values.push_back(col_val.double_val);
      nulls = &column->doubleVal.nulls;
      break;
    case TPrimitiveType::TIMESTAMP:
    case TPrimitiveType::NULL_TYPE:
    case TPrimitiveType::STRING:
    case TPrimitiveType::VARCHAR:
    case TPrimitiveType::DECIMAL:
      is_null = col_val.__isset.string_val;
      column->stringVal.values.push_back(col_val.string_val);
      nulls = &column->stringVal.nulls;
      break;
    default:
      DCHECK(false) << "Unhandled type: "
                    << TypeToString(ThriftToType(type.types[0].scalar_type.type));
      return;
  }

  SetNullBit(row_idx, is_null, nulls);
}

// For V6 and above
void impala::ExprValueToHS2TColumn(const void* value, const TColumnType& type,
    int64_t row_idx, thrift::TColumn* column) {
  string* nulls;
  switch (type.types[0].scalar_type.type) {
    case TPrimitiveType::BOOLEAN:
      column->boolVal.values.push_back(
          value == NULL ? false : *reinterpret_cast<const bool*>(value));
      nulls = &column->boolVal.nulls;
      break;
    case TPrimitiveType::TINYINT:
      column->byteVal.values.push_back(
          value == NULL ? 0 : *reinterpret_cast<const int8_t*>(value));
      nulls = &column->byteVal.nulls;
      break;
    case TPrimitiveType::SMALLINT:
      column->i16Val.values.push_back(
          value == NULL ? 0 : *reinterpret_cast<const int16_t*>(value));
      nulls = &column->i16Val.nulls;
      break;
    case TPrimitiveType::INT:
      column->i32Val.values.push_back(
          value == NULL ? 0 : *reinterpret_cast<const int32_t*>(value));
      nulls = &column->i32Val.nulls;
      break;
    case TPrimitiveType::BIGINT:
      column->i64Val.values.push_back(
          value == NULL ? 0 : *reinterpret_cast<const int64_t*>(value));
      nulls = &column->i64Val.nulls;
      break;
    case TPrimitiveType::FLOAT:
      column->doubleVal.values.push_back(
          value == NULL ? 0.f : *reinterpret_cast<const float*>(value));
      nulls = &column->doubleVal.nulls;
      break;
    case TPrimitiveType::DOUBLE:
      column->doubleVal.values.push_back(
          value == NULL ? 0.0 : *reinterpret_cast<const double*>(value));
      nulls = &column->doubleVal.nulls;
      break;
    case TPrimitiveType::TIMESTAMP:
      column->stringVal.values.push_back("");
      if (value != NULL) {
        RawValue::PrintValue(value, TYPE_TIMESTAMP, -1,
            &(column->stringVal.values.back()));
      }
      nulls = &column->stringVal.nulls;
      break;
    case TPrimitiveType::NULL_TYPE:
    case TPrimitiveType::STRING:
    case TPrimitiveType::VARCHAR:
      column->stringVal.values.push_back("");
      if (value != NULL) {
        const StringValue* str_val = reinterpret_cast<const StringValue*>(value);
        column->stringVal.values.back().assign(
            static_cast<char*>(str_val->ptr), str_val->len);
      }
      nulls = &column->stringVal.nulls;
      break;
    case TPrimitiveType::DECIMAL: {
      // HiveServer2 requires decimal to be presented as string.
      column->stringVal.values.push_back("");
      ColumnType decimalType(type);
      if (value != NULL) {
        switch (decimalType.GetByteSize()) {
          case 4:
            column->stringVal.values.back() =
                reinterpret_cast<const Decimal4Value*>(value)->ToString(type);
            break;
          case 8:
            column->stringVal.values.back() =
                reinterpret_cast<const Decimal8Value*>(value)->ToString(type);
            break;
          case 16:
            column->stringVal.values.back() =
                reinterpret_cast<const Decimal16Value*>(value)->ToString(type);
            break;
          default:
            DCHECK(false) << "bad type: " << type;
        }
      }
      nulls = &column->stringVal.nulls;
      break;
    }
    default:
      DCHECK(false) << "Unhandled type: "
                    << TypeToString(ThriftToType(type.types[0].scalar_type.type));
      return;
  }

  SetNullBit(row_idx, (value == NULL), nulls);
}

// For V1 -> V5
void impala::TColumnValueToHS2TColumnValue(const TColumnValue& col_val,
    const TColumnType& type, thrift::TColumnValue* hs2_col_val) {
  // TODO: Handle complex types.
  DCHECK_EQ(1, type.types.size());
  DCHECK_EQ(TTypeNodeType::SCALAR, type.types[0].type);
  DCHECK_EQ(true, type.types[0].__isset.scalar_type);
  switch (type.types[0].scalar_type.type) {
    case TPrimitiveType::BOOLEAN:
      hs2_col_val->__isset.boolVal = true;
      hs2_col_val->boolVal.value = col_val.bool_val;
      hs2_col_val->boolVal.__isset.value = col_val.__isset.bool_val;
      break;
    case TPrimitiveType::TINYINT:
      hs2_col_val->__isset.byteVal = true;
      hs2_col_val->byteVal.value = col_val.byte_val;
      hs2_col_val->byteVal.__isset.value = col_val.__isset.byte_val;
      break;
    case TPrimitiveType::SMALLINT:
      hs2_col_val->__isset.i16Val = true;
      hs2_col_val->i16Val.value = col_val.short_val;
      hs2_col_val->i16Val.__isset.value = col_val.__isset.short_val;
      break;
    case TPrimitiveType::INT:
      hs2_col_val->__isset.i32Val = true;
      hs2_col_val->i32Val.value = col_val.int_val;
      hs2_col_val->i32Val.__isset.value = col_val.__isset.int_val;
      break;
    case TPrimitiveType::BIGINT:
      hs2_col_val->__isset.i64Val = true;
      hs2_col_val->i64Val.value = col_val.long_val;
      hs2_col_val->i64Val.__isset.value = col_val.__isset.long_val;
      break;
    case TPrimitiveType::FLOAT:
    case TPrimitiveType::DOUBLE:
      hs2_col_val->__isset.doubleVal = true;
      hs2_col_val->doubleVal.value = col_val.double_val;
      hs2_col_val->doubleVal.__isset.value = col_val.__isset.double_val;
      break;
    case TPrimitiveType::DECIMAL:
    case TPrimitiveType::STRING:
    case TPrimitiveType::TIMESTAMP:
    case TPrimitiveType::VARCHAR:
      // HiveServer2 requires timestamp to be presented as string. Note that the .thrift
      // spec says it should be a BIGINT; AFAICT Hive ignores that and produces a string.
      hs2_col_val->__isset.stringVal = true;
      hs2_col_val->stringVal.__isset.value = col_val.__isset.string_val;
      if (col_val.__isset.string_val) {
        hs2_col_val->stringVal.value = col_val.string_val;
      }
      break;
    default:
      DCHECK(false) << "bad type: "
                     << TypeToString(ThriftToType(type.types[0].scalar_type.type));
      break;
  }
}

// For V1 -> V5
void impala::ExprValueToHS2TColumnValue(const void* value, const TColumnType& type,
    thrift::TColumnValue* hs2_col_val) {
  bool not_null = (value != NULL);
  // TODO: Handle complex types.
  DCHECK_EQ(1, type.types.size());
  DCHECK_EQ(TTypeNodeType::SCALAR, type.types[0].type);
  DCHECK_EQ(1, type.types[0].__isset.scalar_type);
  switch (type.types[0].scalar_type.type) {
    case TPrimitiveType::NULL_TYPE:
      // Set NULLs in the bool_val.
      hs2_col_val->__isset.boolVal = true;
      hs2_col_val->boolVal.__isset.value = false;
      break;
    case TPrimitiveType::BOOLEAN:
      hs2_col_val->__isset.boolVal = true;
      if (not_null) hs2_col_val->boolVal.value = *reinterpret_cast<const bool*>(value);
      hs2_col_val->boolVal.__isset.value = not_null;
      break;
    case TPrimitiveType::TINYINT:
      hs2_col_val->__isset.byteVal = true;
      if (not_null) hs2_col_val->byteVal.value = *reinterpret_cast<const int8_t*>(value);
      hs2_col_val->byteVal.__isset.value = not_null;
      break;
    case TPrimitiveType::SMALLINT:
      hs2_col_val->__isset.i16Val = true;
      if (not_null) hs2_col_val->i16Val.value = *reinterpret_cast<const int16_t*>(value);
      hs2_col_val->i16Val.__isset.value = not_null;
      break;
    case TPrimitiveType::INT:
      hs2_col_val->__isset.i32Val = true;
      if (not_null) hs2_col_val->i32Val.value = *reinterpret_cast<const int32_t*>(value);
      hs2_col_val->i32Val.__isset.value = not_null;
      break;
    case TPrimitiveType::BIGINT:
      hs2_col_val->__isset.i64Val = true;
      if (not_null) hs2_col_val->i64Val.value = *reinterpret_cast<const int64_t*>(value);
      hs2_col_val->i64Val.__isset.value = not_null;
      break;
    case TPrimitiveType::FLOAT:
      hs2_col_val->__isset.doubleVal = true;
      if (not_null) hs2_col_val->doubleVal.value = *reinterpret_cast<const float*>(value);
      hs2_col_val->doubleVal.__isset.value = not_null;
      break;
    case TPrimitiveType::DOUBLE:
      hs2_col_val->__isset.doubleVal = true;
      if (not_null) {
        hs2_col_val->doubleVal.value = *reinterpret_cast<const double*>(value);
      }
      hs2_col_val->doubleVal.__isset.value = not_null;
      break;
    case TPrimitiveType::STRING:
    case TPrimitiveType::VARCHAR:
      hs2_col_val->__isset.stringVal = true;
      hs2_col_val->stringVal.__isset.value = not_null;
      if (not_null) {
        const StringValue* string_val = reinterpret_cast<const StringValue*>(value);
        hs2_col_val->stringVal.value.assign(static_cast<char*>(string_val->ptr),
            string_val->len);
      }
      break;
    case TPrimitiveType::TIMESTAMP:
      // HiveServer2 requires timestamp to be presented as string.
      hs2_col_val->__isset.stringVal = true;
      hs2_col_val->stringVal.__isset.value = not_null;
      if (not_null) {
        RawValue::PrintValue(value, TYPE_TIMESTAMP, -1, &(hs2_col_val->stringVal.value));
      }
      break;
    case TPrimitiveType::DECIMAL: {
      // HiveServer2 requires decimal to be presented as string.
      hs2_col_val->__isset.stringVal = true;
      hs2_col_val->stringVal.__isset.value = not_null;
      ColumnType decimalType(type);
      if (not_null) {
        switch (decimalType.GetByteSize()) {
          case 4:
            hs2_col_val->stringVal.value =
              reinterpret_cast<const Decimal4Value*>(value)->ToString(type);
            break;
          case 8:
            hs2_col_val->stringVal.value =
              reinterpret_cast<const Decimal8Value*>(value)->ToString(type);
            break;
          case 16:
            hs2_col_val->stringVal.value =
              reinterpret_cast<const Decimal16Value*>(value)->ToString(type);
            break;
          default:
            DCHECK(false) << "bad type: " << type;
        }
      }
      break;
    }
    default:
      DCHECK(false) << "bad type: "
                     << TypeToString(ThriftToType(type.types[0].scalar_type.type));
      break;
  }
}
