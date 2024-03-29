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

package com.cloudera.impala.analysis;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.catalog.Db;
import com.cloudera.impala.catalog.Function.CompareMode;
import com.cloudera.impala.catalog.ScalarFunction;
import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.Pair;
import com.cloudera.impala.common.Reference;
import com.cloudera.impala.extdatasource.thrift.TComparisonOp;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TExprNodeType;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;

/**
 * Most predicates with two operands.
 *
 */
public class BinaryPredicate extends Predicate {
  private final static Logger LOG = LoggerFactory.getLogger(BinaryPredicate.class);

  public enum Operator {
    EQ("=", "eq", TComparisonOp.EQ),
    NE("!=", "ne", TComparisonOp.NE),
    LE("<=", "le", TComparisonOp.LE),
    GE(">=", "ge", TComparisonOp.GE),
    LT("<", "lt", TComparisonOp.LT),
    GT(">", "gt", TComparisonOp.GT);

    private final String description_;
    private final String name_;
    private final TComparisonOp thriftOp_;

    private Operator(String description, String name, TComparisonOp thriftOp) {
      this.description_ = description;
      this.name_ = name;
      this.thriftOp_ = thriftOp;
    }

    @Override
    public String toString() { return description_; }
    public String getName() { return name_; }
    public TComparisonOp getThriftOp() { return thriftOp_; }

    public Operator converse() {
      switch (this) {
        case EQ: return EQ;
        case NE: return NE;
        case LE: return GE;
        case GE: return LE;
        case LT: return GT;
        case GT: return LT;
        default: throw new IllegalStateException("Invalid operator");
      }
    }
  }

  public static void initBuiltins(Db db) {
    for (Type t: Type.getSupportedTypes()) {
      if (t.isNull()) continue; // NULL is handled through type promotion.
      db.addBuiltin(ScalarFunction.createBuiltinOperator(
          Operator.EQ.getName(), Lists.newArrayList(t, t), Type.BOOLEAN));
      db.addBuiltin(ScalarFunction.createBuiltinOperator(
          Operator.NE.getName(), Lists.newArrayList(t, t), Type.BOOLEAN));
      db.addBuiltin(ScalarFunction.createBuiltinOperator(
          Operator.LE.getName(), Lists.newArrayList(t, t), Type.BOOLEAN));
      db.addBuiltin(ScalarFunction.createBuiltinOperator(
          Operator.GE.getName(), Lists.newArrayList(t, t), Type.BOOLEAN));
      db.addBuiltin(ScalarFunction.createBuiltinOperator(
          Operator.LT.getName(), Lists.newArrayList(t, t), Type.BOOLEAN));
      db.addBuiltin(ScalarFunction.createBuiltinOperator(
          Operator.GT.getName(), Lists.newArrayList(t, t), Type.BOOLEAN));
    }
  }

  private final Operator op_;

  public Operator getOp() { return op_; }

  public BinaryPredicate(Operator op, Expr e1, Expr e2) {
    super();
    this.op_ = op;
    Preconditions.checkNotNull(e1);
    children_.add(e1);
    Preconditions.checkNotNull(e2);
    children_.add(e2);
  }

  protected BinaryPredicate(BinaryPredicate other) {
    super(other);
    op_ = other.op_;
  }

  @Override
  public String toSqlImpl() {
    return getChild(0).toSql() + " " + op_.toString() + " " + getChild(1).toSql();
  }

  @Override
  protected void toThrift(TExprNode msg) {
    Preconditions.checkState(children_.size() == 2);
    // Cannot serialize a nested predicate.
    Preconditions.checkState(!contains(Subquery.class));
    // This check is important because we often clone and/or evaluate predicates,
    // and it's easy to get the casting logic wrong, e.g., cloned predicates
    // with expr substitutions need to be re-analyzed with reanalyze().
    Preconditions.checkState(getChild(0).getType().getPrimitiveType() ==
                             getChild(1).getType().getPrimitiveType(),
        "child 0 type: " + getChild(0).getType() +
        " child 1 type: " + getChild(1).getType());
    msg.node_type = TExprNodeType.FUNCTION_CALL;
  }

  @Override
  public String debugString() {
    return Objects.toStringHelper(this)
        .add("op", op_)
        .addValue(super.debugString())
        .toString();
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    if (isAnalyzed_) return;
    super.analyze(analyzer);

    convertNumericLiteralsFromDecimal(analyzer);
    fn_ = getBuiltinFunction(analyzer, op_.getName(), collectChildReturnTypes(),
        CompareMode.IS_SUPERTYPE_OF);
    if (fn_ == null) {
      // Construct an appropriate error message and throw an AnalysisException.
      String errMsg = "operands of type " + getChild(0).getType().toSql() + " and " +
            getChild(1).getType().toSql()  + " are not comparable: " + toSql();

      // Check if any of the children is a Subquery that does not return a
      // scalar.
      for (Expr expr: children_) {
        if (expr instanceof Subquery && !expr.getType().isScalarType()) {
          errMsg = "Subquery must return a single row: " + expr.toSql();
          break;
        }
      }

      throw new AnalysisException(errMsg);
    }
    Preconditions.checkState(fn_.getReturnType().isBoolean());

    ArrayList<Expr> subqueries = Lists.newArrayList();
    collectAll(Predicates.instanceOf(Subquery.class), subqueries);
    if (subqueries.size() > 1) {
      // TODO Remove that restriction when we add support for independent subquery
      // evaluation.
      throw new AnalysisException("Multiple subqueries are not supported in binary " +
          "predicates: " + toSql());
    }
    if (contains(InPredicate.class) || contains(ExistsPredicate.class)) {
      throw new AnalysisException("IN and/or EXISTS subquery predicates are not " +
          "supported in binary predicates: " + toSql());
    }

    // Don't perform any casting for predicates with subqueries here. Any casting
    // required will be performed when the subquery is unnested.
    if (!contains(Subquery.class)) castForFunctionCall(true);

    // determine selectivity
    // TODO: Compute selectivity for nested predicates
    Reference<SlotRef> slotRefRef = new Reference<SlotRef>();
    if (op_ == Operator.EQ
        && isSingleColumnPredicate(slotRefRef, null)
        && slotRefRef.getRef().getNumDistinctValues() > 0) {
      Preconditions.checkState(slotRefRef.getRef() != null);
      selectivity_ = 1.0 / slotRefRef.getRef().getNumDistinctValues();
      selectivity_ = Math.max(0, Math.min(1, selectivity_));
    } else {
      // TODO: improve using histograms, once they show up
      selectivity_ = Expr.DEFAULT_SELECTIVITY;
    }
  }

  /**
   * If predicate is of the form "<slotref> <op> <expr>", returns expr,
   * otherwise returns null. Slotref may be wrapped in a CastExpr.
   * TODO: revisit CAST handling at the caller
   */
  public Expr getSlotBinding(SlotId id) {
    // check left operand
    SlotRef slotRef = getChild(0).unwrapSlotRef(false);
    if (slotRef != null && slotRef.getSlotId() == id) return getChild(1);
    // check right operand
    slotRef = getChild(1).unwrapSlotRef(false);
    if (slotRef != null && slotRef.getSlotId() == id) return getChild(0);
    return null;
  }

  /**
   * If e is an equality predicate between two slots that only require implicit
   * casts, returns those two slots; otherwise returns null.
   */
  public static Pair<SlotId, SlotId> getEqSlots(Expr e) {
    if (!(e instanceof BinaryPredicate)) return null;
    return ((BinaryPredicate) e).getEqSlots();
  }

  /**
   * If this is an equality predicate between two slots that only require implicit
   * casts, returns those two slots; otherwise returns null.
   */
  @Override
  public Pair<SlotId, SlotId> getEqSlots() {
    if (op_ != Operator.EQ) return null;
    SlotRef lhs = getChild(0).unwrapSlotRef(true);
    if (lhs == null) return null;
    SlotRef rhs = getChild(1).unwrapSlotRef(true);
    if (rhs == null) return null;
    return new Pair<SlotId, SlotId>(lhs.getSlotId(), rhs.getSlotId());
  }

  /**
   * If predicate is of the form "<SlotRef> op <Expr>" or "<Expr> op <SlotRef>",
   * returns the SlotRef, otherwise returns null.
   */
  @Override
  public SlotRef getBoundSlot() {
    SlotRef slotRef = getChild(0).unwrapSlotRef(true);
    if (slotRef != null) return slotRef;
    return getChild(1).unwrapSlotRef(true);
  }

  /**
   * Negates a BinaryPredicate.
   */
  @Override
  public Expr negate() {
    Operator newOp = null;
    switch (op_) {
      case EQ:
        newOp = Operator.NE;
        break;
      case NE:
        newOp = Operator.EQ;
        break;
      case LT:
        newOp = Operator.GE;
        break;
      case LE:
        newOp = Operator.GT;
        break;
      case GE:
        newOp = Operator.LT;
        break;
      case GT:
        newOp = Operator.LE;
        break;
    }
    return new BinaryPredicate(newOp, getChild(0), getChild(1));
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj)) return false;
    BinaryPredicate other = (BinaryPredicate) obj;
    return op_.equals(other.op_);
  }

  @Override
  public Expr clone() { return new BinaryPredicate(this); }
}
