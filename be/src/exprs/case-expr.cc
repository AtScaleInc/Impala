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

#include "exprs/case-expr.h"

#include "codegen/codegen-anyval.h"
#include "codegen/llvm-codegen.h"
#include "exprs/anyval-util.h"
#include "exprs/expr-context.h"
#include "exprs/conditional-functions.h"
#include "runtime/runtime-state.h"

#include "gen-cpp/Exprs_types.h"

using namespace llvm;
using namespace std;

namespace impala {

struct CaseExprState {
  // Space to store the values being compared in the interpreted path. This makes it
  // easier to pass around AnyVal subclasses. Allocated from the runtime state's object
  // pool in Prepare().
  AnyVal* case_val;
  AnyVal* when_val;
};

CaseExpr::CaseExpr(const TExprNode& node)
  : Expr(node),
    has_case_expr_(node.case_expr.has_case_expr),
    has_else_expr_(node.case_expr.has_else_expr) {
}

Status CaseExpr::Prepare(RuntimeState* state, const RowDescriptor& desc,
                         ExprContext* ctx) {
  RETURN_IF_ERROR(Expr::Prepare(state, desc, ctx));
  RegisterFunctionContext(ctx, state);
  return Status::OK;
}

Status CaseExpr::Open(RuntimeState* state, ExprContext* ctx,
                      FunctionContext::FunctionStateScope scope) {
  RETURN_IF_ERROR(Expr::Open(state, ctx, scope));
  FunctionContext* fn_ctx = ctx->fn_context(context_index_);
  CaseExprState* case_state =
      reinterpret_cast<CaseExprState*>(fn_ctx->Allocate(sizeof(CaseExprState)));
  fn_ctx->SetFunctionState(FunctionContext::THREAD_LOCAL, case_state);
  if (has_case_expr_) {
    case_state->case_val = CreateAnyVal(state->obj_pool(), children_[0]->type());
    case_state->when_val = CreateAnyVal(state->obj_pool(), children_[1]->type());
  } else {
    case_state->case_val = CreateAnyVal(state->obj_pool(), TYPE_BOOLEAN);
    case_state->when_val = CreateAnyVal(state->obj_pool(), children_[0]->type());
  }
  return Status::OK;
}

void CaseExpr::Close(RuntimeState* state, ExprContext* ctx,
                     FunctionContext::FunctionStateScope scope) {
  if (context_index_ != -1) {
    FunctionContext* fn_ctx = ctx->fn_context(context_index_);
    void* case_state = fn_ctx->GetFunctionState(FunctionContext::THREAD_LOCAL);
    fn_ctx->Free(reinterpret_cast<uint8_t*>(case_state));
  }
  Expr::Close(state, ctx, scope);
}

string CaseExpr::DebugString() const {
  stringstream out;
  out << "CaseExpr(has_case_expr=" << has_case_expr_
      << " has_else_expr=" << has_else_expr_
      << " " << Expr::DebugString() << ")";
  return out.str();
}

// Sample IR output when there is a case expression and else expression
// define i16 @CaseExpr(%"class.impala::ExprContext"* %context,
//                      %"class.impala::TupleRow"* %row) #20 {
// eval_case_expr:
//   %case_val = call i64 @GetSlotRef(%"class.impala::ExprContext"* %context,
//                                    %"class.impala::TupleRow"* %row)
//   %is_null = trunc i64 %case_val to i1
//   br i1 %is_null, label %return_else_expr, label %eval_first_when_expr
// 
// eval_first_when_expr:                             ; preds = %eval_case_expr
//   %when_val = call i64 @Literal(%"class.impala::ExprContext"* %context,
//                                 %"class.impala::TupleRow"* %row)
//   %is_null1 = trunc i64 %when_val to i1
//   br i1 %is_null1, label %return_else_expr, label %check_when_expr_block
// 
// check_when_expr_block:                            ; preds = %eval_first_when_expr
//   %0 = ashr i64 %when_val, 32
//   %1 = trunc i64 %0 to i32
//   %2 = ashr i64 %case_val, 32
//   %3 = trunc i64 %2 to i32
//   %eq = icmp eq i32 %3, %1
//   br i1 %eq, label %return_then_expr, label %return_else_expr
// 
// return_then_expr:                                 ; preds = %check_when_expr_block
//   %then_val = call i16 @Literal12(%"class.impala::ExprContext"* %context,
//                                   %"class.impala::TupleRow"* %row)
//   ret i16 %then_val
// 
// return_else_expr:                                 ; preds = %check_when_expr_block, %eval_first_when_expr, %eval_case_expr
//   %else_val = call i16 @Literal13(%"class.impala::ExprContext"* %context,
//                                   %"class.impala::TupleRow"* %row)
//   ret i16 %else_val
// }
//
// Sample IR output when there is case expression and no else expression
// define i16 @CaseExpr(%"class.impala::ExprContext"* %context,
//                      %"class.impala::TupleRow"* %row) #20 {
// eval_case_expr:
//   %case_val = call i64 @GetSlotRef(%"class.impala::ExprContext"* %context,
//                                    %"class.impala::TupleRow"* %row)
//   %is_null = trunc i64 %case_val to i1
//   br i1 %is_null, label %return_null, label %eval_first_when_expr
// 
// eval_first_when_expr:                             ; preds = %eval_case_expr
//   %when_val = call i64 @Literal(%"class.impala::ExprContext"* %context,
//                                 %"class.impala::TupleRow"* %row)
//   %is_null1 = trunc i64 %when_val to i1
//   br i1 %is_null1, label %return_null, label %check_when_expr_block
// 
// check_when_expr_block:                            ; preds = %eval_first_when_expr
//   %0 = ashr i64 %when_val, 32
//   %1 = trunc i64 %0 to i32
//   %2 = ashr i64 %case_val, 32
//   %3 = trunc i64 %2 to i32
//   %eq = icmp eq i32 %3, %1
//   br i1 %eq, label %return_then_expr, label %return_null
// 
// return_then_expr:                                 ; preds = %check_when_expr_block
//   %then_val = call i16 @Literal12(%"class.impala::ExprContext"* %context,
//                                   %"class.impala::TupleRow"* %row)
//   ret i16 %then_val
// 
// return_null:                                      ; preds = %check_when_expr_block, %eval_first_when_expr, %eval_case_expr
//   ret i16 1
// }
//
// Sample IR output when there is no case expr and else expression
// define i16 @CaseExpr(%"class.impala::ExprContext"* %context,
//                      %"class.impala::TupleRow"* %row) #20 {
// eval_first_when_expr:
//   %when_val = call i16 @Eq_IntVal_IntValWrapper1(
//       %"class.impala::ExprContext"* %context, %"class.impala::TupleRow"* %row)
//   %is_null = trunc i16 %when_val to i1
//   br i1 %is_null, label %return_else_expr, label %check_when_expr_block
// 
// check_when_expr_block:                            ; preds = %eval_first_when_expr
//   %0 = ashr i16 %when_val, 8
//   %1 = trunc i16 %0 to i8
//   %val = trunc i8 %1 to i1
//   br i1 %val, label %return_then_expr, label %return_else_expr
// 
// return_then_expr:                                 ; preds = %check_when_expr_block
//   %then_val = call i16 @Literal14(%"class.impala::ExprContext"* %context,
//                                   %"class.impala::TupleRow"* %row)
//   ret i16 %then_val
// 
// return_else_expr:                                 ; preds = %check_when_expr_block, %eval_first_when_expr
//   %else_val = call i16 @Literal15(%"class.impala::ExprContext"* %context,
//                                   %"class.impala::TupleRow"* %row)
//   ret i16 %else_val
// }
Status CaseExpr::GetCodegendComputeFn(RuntimeState* state, Function** fn) {
  if (ir_compute_fn_ != NULL) {
    *fn = ir_compute_fn_;
    return Status::OK;
  }

  const int num_children = GetNumChildren();
  Function* child_fns[num_children];
  for (int i = 0; i < num_children; ++i) {
    RETURN_IF_ERROR(children()[i]->GetCodegendComputeFn(state, &child_fns[i]));
  }

  LlvmCodeGen* codegen = state->codegen();
  LLVMContext& context = codegen->context();
  LlvmCodeGen::LlvmBuilder builder(context);

  Value* args[2];
  Function* function = CreateIrFunctionPrototype(codegen, "CaseExpr", &args);
  BasicBlock* eval_case_expr_block = NULL;

  // This is the block immediately after the when/then exprs. It will either point to a
  // block which returns the else expr, or returns NULL if no else expr is specified.
  BasicBlock* default_value_block = BasicBlock::Create(
      context, has_else_expr() ? "return_else_expr" : "return_null", function);

  // If there is a case expression, create a block to evaluate it.
  CodegenAnyVal case_val;
  BasicBlock* eval_first_when_expr_block = BasicBlock::Create(
      context, "eval_first_when_expr", function, default_value_block);
  BasicBlock* current_when_expr_block = eval_first_when_expr_block;
  if (has_case_expr()) {
    // Need at least case, when and then expr, and optionally an else expr
    DCHECK_GE(num_children, (has_else_expr()) ? 4 : 3);
    // If there is a case expr, create block eval_case_expr to evaluate the
    // case expr. Place this block before eval_first_when_expr_block
    eval_case_expr_block = BasicBlock::Create(context, "eval_case_expr",
        function, eval_first_when_expr_block);
    builder.SetInsertPoint(eval_case_expr_block);
    case_val = CodegenAnyVal::CreateCallWrapped(
        codegen, &builder, children()[0]->type(), child_fns[0], args, "case_val");
    builder.CreateCondBr(
        case_val.GetIsNull(), default_value_block, eval_first_when_expr_block);
  } else {
    DCHECK_GE(num_children, (has_else_expr()) ? 3 : 2);
  }

  const int loop_end = (has_else_expr()) ? num_children - 1 : num_children;
  const int last_loop_iter = loop_end - 2;
  // The loop increments by two each time, because each iteration handles one when/then
  // pair. Both when and then subexpressions are single children. If there is a case expr
  // start loop at index 1. (case expr is children()[0] and has already be evaluated.
  for (int i = (has_case_expr()) ? 1 : 0; i < loop_end; i += 2) {
    BasicBlock* check_when_expr_block = BasicBlock::Create(
        context, "check_when_expr_block", function, default_value_block);
    BasicBlock* return_then_expr_block =
        BasicBlock::Create(context, "return_then_expr", function, default_value_block);

    // continue_or_exit_block either points to the next eval_next_when_expr block,
    // or points to the defaut_value_block if there are no more when/then expressions.
    BasicBlock* continue_or_exit_block = NULL;
    if (i == last_loop_iter) {
      continue_or_exit_block = default_value_block;
    } else {
      continue_or_exit_block = BasicBlock::Create(
          context, "eval_next_when_expr", function, default_value_block);
    }

    // Get the child value of the when statement. If NULL simply continue to next when
    // statement
    builder.SetInsertPoint(current_when_expr_block);
    CodegenAnyVal when_val = CodegenAnyVal::CreateCallWrapped(
        codegen, &builder, children()[i]->type(), child_fns[i], args, "when_val");
    builder.CreateCondBr(
        when_val.GetIsNull(), continue_or_exit_block, check_when_expr_block);

    builder.SetInsertPoint(check_when_expr_block);
    if (has_case_expr()) {
      // Compare for equality
      Value* is_equal = case_val.Eq(&when_val);
      builder.CreateCondBr(is_equal, return_then_expr_block, continue_or_exit_block);
    } else {
      builder.CreateCondBr(
          when_val.GetVal(), return_then_expr_block, continue_or_exit_block);
    }

    builder.SetInsertPoint(return_then_expr_block);

    // Eval and return then value
    Value* then_val = CodegenAnyVal::CreateCall(
        codegen, &builder, child_fns[i+1], args, "then_val");
    builder.CreateRet(then_val);

    current_when_expr_block = continue_or_exit_block;
  }

  builder.SetInsertPoint(default_value_block);
  if (has_else_expr()) {
    Value* else_val = CodegenAnyVal::CreateCall(
        codegen, &builder, child_fns[num_children - 1], args, "else_val");
    builder.CreateRet(else_val);
  } else {
    builder.CreateRet(CodegenAnyVal::GetNullVal(codegen, type()));
  }

  *fn = codegen->FinalizeFunction(function);
  DCHECK(*fn != NULL);
  ir_compute_fn_ = *fn;
  return Status::OK;
}

void CaseExpr::GetChildVal(int child_idx, ExprContext* ctx, TupleRow* row, AnyVal* dst) {
  switch (children()[child_idx]->type().type) {
    case TYPE_BOOLEAN:
      *reinterpret_cast<BooleanVal*>(dst) = children()[child_idx]->GetBooleanVal(ctx, row);
      break;
    case TYPE_TINYINT:
      *reinterpret_cast<TinyIntVal*>(dst) = children()[child_idx]->GetTinyIntVal(ctx, row);
      break;
    case TYPE_SMALLINT:
      *reinterpret_cast<SmallIntVal*>(dst) =
          children()[child_idx]->GetSmallIntVal(ctx, row);
      break;
    case TYPE_INT:
      *reinterpret_cast<IntVal*>(dst) = children()[child_idx]->GetIntVal(ctx, row);
      break;
    case TYPE_BIGINT:
      *reinterpret_cast<BigIntVal*>(dst) = children()[child_idx]->GetBigIntVal(ctx, row);
      break;
    case TYPE_FLOAT:
      *reinterpret_cast<FloatVal*>(dst) = children()[child_idx]->GetFloatVal(ctx, row);
      break;
    case TYPE_DOUBLE:
      *reinterpret_cast<DoubleVal*>(dst) = children()[child_idx]->GetDoubleVal(ctx, row);
      break;
    case TYPE_TIMESTAMP:
      *reinterpret_cast<TimestampVal*>(dst) =
          children()[child_idx]->GetTimestampVal(ctx, row);
      break;
    case TYPE_STRING:
      *reinterpret_cast<StringVal*>(dst) = children()[child_idx]->GetStringVal(ctx, row);
      break;
    case TYPE_DECIMAL:
      *reinterpret_cast<DecimalVal*>(dst) = children()[child_idx]->GetDecimalVal(ctx, row);
      break;
    default:
      DCHECK(false) << children()[child_idx]->type();
  }
}

bool CaseExpr::AnyValEq(const ColumnType& type, const AnyVal* v1, const AnyVal* v2) {
  switch (type.type) {
    case TYPE_BOOLEAN:
      return *reinterpret_cast<const BooleanVal*>(v1) ==
             *reinterpret_cast<const BooleanVal*>(v2);
    case TYPE_TINYINT:
      return *reinterpret_cast<const TinyIntVal*>(v1) ==
             *reinterpret_cast<const TinyIntVal*>(v2);
    case TYPE_SMALLINT:
      return *reinterpret_cast<const SmallIntVal*>(v1) ==
             *reinterpret_cast<const SmallIntVal*>(v2);
    case TYPE_INT:
      return *reinterpret_cast<const IntVal*>(v1) ==
             *reinterpret_cast<const IntVal*>(v2);
    case TYPE_BIGINT:
      return *reinterpret_cast<const BigIntVal*>(v1) ==
             *reinterpret_cast<const BigIntVal*>(v2);
    case TYPE_FLOAT:
      return *reinterpret_cast<const FloatVal*>(v1) ==
             *reinterpret_cast<const FloatVal*>(v2);
    case TYPE_DOUBLE:
      return *reinterpret_cast<const DoubleVal*>(v1) ==
             *reinterpret_cast<const DoubleVal*>(v2);
    case TYPE_TIMESTAMP:
      return *reinterpret_cast<const TimestampVal*>(v1) ==
             *reinterpret_cast<const TimestampVal*>(v2);
    case TYPE_STRING:
      return *reinterpret_cast<const StringVal*>(v1) ==
             *reinterpret_cast<const StringVal*>(v2);
    case TYPE_DECIMAL:
      return *reinterpret_cast<const DecimalVal*>(v1) ==
             *reinterpret_cast<const DecimalVal*>(v2);
    default:
      DCHECK(false) << type;
      return false;
  }
}

#define CASE_COMPUTE_FN(THEN_TYPE) \
  THEN_TYPE CaseExpr::Get##THEN_TYPE(ExprContext* ctx, TupleRow* row) { \
    FunctionContext* fn_ctx = ctx->fn_context(context_index_); \
    CaseExprState* state = reinterpret_cast<CaseExprState*>( \
        fn_ctx->GetFunctionState(FunctionContext::THREAD_LOCAL)); \
    DCHECK(state->case_val != NULL); \
    DCHECK(state->when_val != NULL); \
    int num_children = GetNumChildren(); \
    if (has_case_expr()) {                               \
      /* All case and when exprs return the same type */ \
      /* (we guaranteed that during analysis). */ \
      GetChildVal(0, ctx, row, state->case_val); \
    } else { \
      /* If there's no case expression, compare the when values to "true". */ \
      *reinterpret_cast<BooleanVal*>(state->case_val) = BooleanVal(true); \
    } \
    if (state->case_val->is_null) { \
      if (has_else_expr()) { \
        /* Return else value. */ \
        return children()[num_children - 1]->Get##THEN_TYPE(ctx, row); \
      } else { \
        return THEN_TYPE::null(); \
      } \
    } \
    int loop_start = has_case_expr() ? 1 : 0; \
    int loop_end = (has_else_expr()) ? num_children - 1 : num_children; \
    for (int i = loop_start; i < loop_end; i += 2) { \
      GetChildVal(i, ctx, row, state->when_val); \
      if (state->when_val->is_null) continue; \
      if (AnyValEq(children()[0]->type(), state->case_val, state->when_val)) {  \
        /* Return then value. */ \
        return children()[i + 1]->Get##THEN_TYPE(ctx, row); \
      } \
    } \
    if (has_else_expr()) { \
      /* Return else value. */ \
      return children()[num_children - 1]->Get##THEN_TYPE(ctx, row); \
    } \
    return THEN_TYPE::null(); \
  }

CASE_COMPUTE_FN(BooleanVal)
CASE_COMPUTE_FN(TinyIntVal)
CASE_COMPUTE_FN(SmallIntVal)
CASE_COMPUTE_FN(IntVal)
CASE_COMPUTE_FN(BigIntVal)
CASE_COMPUTE_FN(FloatVal)
CASE_COMPUTE_FN(DoubleVal)
CASE_COMPUTE_FN(StringVal)
CASE_COMPUTE_FN(TimestampVal)
CASE_COMPUTE_FN(DecimalVal)

}
