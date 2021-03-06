/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.engine.planner;

import org.apache.tajo.algebra.*;
import org.apache.tajo.catalog.CatalogService;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.catalog.FunctionDesc;
import org.apache.tajo.catalog.exception.NoSuchFunctionException;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.common.TajoDataTypes;
import org.apache.tajo.datum.*;
import org.apache.tajo.engine.eval.*;
import org.apache.tajo.engine.function.AggFunction;
import org.apache.tajo.engine.function.GeneralFunction;
import org.apache.tajo.engine.planner.logical.NodeType;
import org.apache.tajo.exception.InternalException;
import org.joda.time.DateTime;

import java.util.Stack;

/**
 * <code>ExprAnnotator</code> makes an annotated expression called <code>EvalNode</code> from an
 * {@link org.apache.tajo.algebra.Expr}. It visits descendants recursively from a given expression, and finally
 * it returns an EvalNode.
 */
public class ExprAnnotator extends BaseAlgebraVisitor<ExprAnnotator.Context, EvalNode> {
  private CatalogService catalog;

  public ExprAnnotator(CatalogService catalog) {
    this.catalog = catalog;
  }

  static class Context {
    LogicalPlan plan;
    LogicalPlan.QueryBlock currentBlock;

    public Context(LogicalPlan plan, LogicalPlan.QueryBlock block) {
      this.plan = plan;
      this.currentBlock = block;
    }
  }

  public EvalNode createEvalNode(LogicalPlan plan, LogicalPlan.QueryBlock block, Expr expr)
      throws PlanningException {
    Context context = new Context(plan, block);
    return visit(context, new Stack<Expr>(), expr);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Logical Operator Section
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public EvalNode visitAnd(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.AND, left, right);
  }

  @Override
  public EvalNode visitOr(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.OR, left, right);
  }

  @Override
  public EvalNode visitNot(Context ctx, Stack<Expr> stack, NotExpr expr) throws PlanningException {
    stack.push(expr);
    EvalNode child = visit(ctx, stack, expr.getChild());
    stack.pop();
    return new NotEval(child);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Comparison Predicates Section
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  @Override
  public EvalNode visitEquals(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    return visitCommonComparison(ctx, stack, expr);
  }

  @Override
  public EvalNode visitNotEquals(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    return visitCommonComparison(ctx, stack, expr);
  }

  @Override
  public EvalNode visitLessThan(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    return visitCommonComparison(ctx, stack, expr);
  }

  @Override
  public EvalNode visitLessThanOrEquals(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    return visitCommonComparison(ctx, stack, expr);
  }

  @Override
  public EvalNode visitGreaterThan(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    return visitCommonComparison(ctx, stack, expr);
  }

  @Override
  public EvalNode visitGreaterThanOrEquals(Context ctx, Stack<Expr> stack, BinaryOperator expr)
      throws PlanningException {
    return visitCommonComparison(ctx, stack, expr);
  }

  public EvalNode visitCommonComparison(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    EvalType evalType;
    switch (expr.getType()) {
      case Equals:
        evalType = EvalType.EQUAL;
        break;
      case NotEquals:
        evalType = EvalType.NOT_EQUAL;
        break;
      case LessThan:
        evalType = EvalType.LTH;
        break;
      case LessThanOrEquals:
        evalType = EvalType.LEQ;
        break;
      case GreaterThan:
        evalType = EvalType.GTH;
        break;
      case GreaterThanOrEquals:
        evalType = EvalType.GEQ;
        break;
      default:
      throw new IllegalStateException("Wrong Expr Type: " + expr.getType());
    }

    return new BinaryEval(evalType, left, right);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Other Predicates Section
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public EvalNode visitBetween(Context ctx, Stack<Expr> stack, BetweenPredicate between) throws PlanningException {
    stack.push(between);
    EvalNode predicand = visit(ctx, stack, between.predicand());
    EvalNode begin = visit(ctx, stack, between.begin());
    EvalNode end = visit(ctx, stack, between.end());
    stack.pop();

    BetweenPredicateEval betweenEval = new BetweenPredicateEval(
        between.isNot(),
        between.isSymmetric(),
        predicand, begin, end);
    return betweenEval;
  }

  @Override
  public EvalNode visitCaseWhen(Context ctx, Stack<Expr> stack, CaseWhenPredicate caseWhen) throws PlanningException {
    CaseWhenEval caseWhenEval = new CaseWhenEval();

    EvalNode condition;
    EvalNode result;
    for (CaseWhenPredicate.WhenExpr when : caseWhen.getWhens()) {
      condition = visit(ctx, stack, when.getCondition());
      result = visit(ctx, stack, when.getResult());
      caseWhenEval.addWhen(condition, result);
    }

    if (caseWhen.hasElseResult()) {
      caseWhenEval.setElseResult(visit(ctx, stack, caseWhen.getElseResult()));
    }

    return caseWhenEval;
  }

  @Override
  public EvalNode visitIsNullPredicate(Context ctx, Stack<Expr> stack, IsNullPredicate expr) throws PlanningException {
    stack.push(expr);
    EvalNode child = visit(ctx, stack, expr.getPredicand());
    stack.pop();
    return new IsNullEval(expr.isNot(), child);
  }

  @Override
  public EvalNode visitInPredicate(Context ctx, Stack<Expr> stack, InPredicate expr) throws PlanningException {
    stack.push(expr);
    EvalNode lhs = visit(ctx, stack, expr.getLeft());
    RowConstantEval rowConstantEval = (RowConstantEval) visit(ctx, stack, expr.getInValue());
    stack.pop();
    return new InEval(lhs, rowConstantEval, expr.isNot());
  }

  @Override
  public EvalNode visitValueListExpr(Context ctx, Stack<Expr> stack, ValueListExpr expr) throws PlanningException {
    Datum[] values = new Datum[expr.getValues().length];
    ConstEval [] constEvals = new ConstEval[expr.getValues().length];
    for (int i = 0; i < expr.getValues().length; i++) {
      constEvals[i] = (ConstEval) visit(ctx, stack, expr.getValues()[i]);
      values[i] = constEvals[i].getValue();
    }
    return new RowConstantEval(values);
  }

  @Override
  public EvalNode visitExistsPredicate(Context ctx, Stack<Expr> stack, ExistsPredicate expr) throws PlanningException {
    throw new PlanningException("Cannot support EXISTS clause yet");
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // String Operator or Pattern Matching Predicates Section
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  @Override
  public EvalNode visitLikePredicate(Context ctx, Stack<Expr> stack, PatternMatchPredicate expr)
      throws PlanningException {
    return visitPatternMatchPredicate(ctx, stack, expr);
  }

  @Override
  public EvalNode visitSimilarToPredicate(Context ctx, Stack<Expr> stack, PatternMatchPredicate expr)
      throws PlanningException {
    return visitPatternMatchPredicate(ctx, stack, expr);
  }

  @Override
  public EvalNode visitRegexpPredicate(Context ctx, Stack<Expr> stack, PatternMatchPredicate expr)
      throws PlanningException {
    return visitPatternMatchPredicate(ctx, stack, expr);
  }

  @Override
  public EvalNode visitConcatenate(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.CONCATENATE, left, right);
  }

  private EvalNode visitPatternMatchPredicate(Context ctx, Stack<Expr> stack, PatternMatchPredicate expr)
      throws PlanningException {
    EvalNode field = visit(ctx, stack, expr.getPredicand());
    ConstEval pattern = (ConstEval) visit(ctx, stack, expr.getPattern());

    // A pattern is a const value in pattern matching predicates.
    // In a binary expression, the result is always null if a const value in left or right side is null.
    if (pattern.getValue() instanceof NullDatum) {
      return new ConstEval(NullDatum.get());
    } else {
      if (expr.getType() == OpType.LikePredicate) {
        return new LikePredicateEval(expr.isNot(), field, pattern, expr.isCaseInsensitive());
      } else if (expr.getType() == OpType.SimilarToPredicate) {
        return new SimilarToPredicateEval(expr.isNot(), field, pattern);
      } else {
        return new RegexPredicateEval(expr.isNot(), field, pattern, expr.isCaseInsensitive());
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Arithmetic Operators
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public EvalNode visitPlus(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.PLUS, left, right);
  }

  @Override
  public EvalNode visitMinus(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.MINUS, left, right);
  }

  @Override
  public EvalNode visitMultiply(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.MULTIPLY, left, right);
  }

  @Override
  public EvalNode visitDivide(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.DIVIDE, left, right);
  }

  @Override
  public EvalNode visitModular(Context ctx, Stack<Expr> stack, BinaryOperator expr) throws PlanningException {
    stack.push(expr);
    EvalNode left = visit(ctx, stack, expr.getLeft());
    EvalNode right = visit(ctx, stack, expr.getRight());
    stack.pop();

    return new BinaryEval(EvalType.MODULAR, left, right);
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Other Expressions
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public EvalNode visitSign(Context ctx, Stack<Expr> stack, SignedExpr expr) throws PlanningException {
    stack.push(expr);
    EvalNode numericExpr = visit(ctx, stack, expr.getChild());
    stack.pop();

    if (expr.isNegative()) {
      return new SignedEval(expr.isNegative(), numericExpr);
    } else {
      return numericExpr;
    }
  }

  @Override
  public EvalNode visitColumnReference(Context ctx, Stack<Expr> stack, ColumnReferenceExpr expr)
      throws PlanningException {
    Column column = ctx.plan.resolveColumn(ctx.currentBlock, expr);
    return new FieldEval(column);
  }

  @Override
  public EvalNode visitTargetExpr(Context ctx, Stack<Expr> stack, NamedExpr expr) throws PlanningException {
    throw new PlanningException("ExprAnnotator cannot take NamedExpr");
  }

  @Override
  public EvalNode visitFunction(Context ctx, Stack<Expr> stack, FunctionExpr expr) throws PlanningException {
    stack.push(expr); // <--- Push

    // Given parameters
    Expr[] params = expr.getParams();
    if (params == null) {
      params = new Expr[0];
    }

    EvalNode[] givenArgs = new EvalNode[params.length];
    TajoDataTypes.DataType[] paramTypes = new TajoDataTypes.DataType[params.length];

    for (int i = 0; i < params.length; i++) {
      givenArgs[i] = visit(ctx, stack, params[i]);
      paramTypes[i] = givenArgs[i].getValueType();
    }

    stack.pop(); // <--- Pop

    if (!catalog.containFunction(expr.getSignature(), paramTypes)) {
      throw new NoSuchFunctionException(expr.getSignature(), paramTypes);
    }

    FunctionDesc funcDesc = catalog.getFunction(expr.getSignature(), paramTypes);

    try {
    CatalogProtos.FunctionType functionType = funcDesc.getFuncType();
    if (functionType == CatalogProtos.FunctionType.GENERAL
        || functionType == CatalogProtos.FunctionType.UDF) {
      return new GeneralFunctionEval(funcDesc, (GeneralFunction) funcDesc.newInstance(), givenArgs);
    } else if (functionType == CatalogProtos.FunctionType.AGGREGATION
        || functionType == CatalogProtos.FunctionType.UDA) {
      if (!ctx.currentBlock.hasNode(NodeType.GROUP_BY)) {
        ctx.currentBlock.setAggregationRequire();
      }
      return new AggregationFunctionCallEval(funcDesc, (AggFunction) funcDesc.newInstance(), givenArgs);
    } else if (functionType == CatalogProtos.FunctionType.DISTINCT_AGGREGATION
        || functionType == CatalogProtos.FunctionType.DISTINCT_UDA) {
      throw new PlanningException("Unsupported function: " + funcDesc.toString());
    } else {
      throw new PlanningException("Unsupported Function Type: " + functionType.name());
    }
    } catch (InternalException e) {
      throw new PlanningException(e);
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // General Set Section
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public EvalNode visitCountRowsFunction(Context ctx, Stack<Expr> stack, CountRowsFunctionExpr expr)
      throws PlanningException {
    FunctionDesc countRows = catalog.getFunction("count", CatalogProtos.FunctionType.AGGREGATION,
        new TajoDataTypes.DataType[] {});
    if (countRows == null) {
      throw new NoSuchFunctionException(countRows.getSignature(), new TajoDataTypes.DataType[]{});
    }

    try {
      ctx.currentBlock.setAggregationRequire();

      return new AggregationFunctionCallEval(countRows, (AggFunction) countRows.newInstance(),
          new EvalNode[] {});
    } catch (InternalException e) {
      throw new NoSuchFunctionException(countRows.getSignature(), new TajoDataTypes.DataType[]{});
    }
  }

  @Override
  public EvalNode visitGeneralSetFunction(Context ctx, Stack<Expr> stack, GeneralSetFunctionExpr setFunction)
      throws PlanningException {

    Expr[] params = setFunction.getParams();
    EvalNode[] givenArgs = new EvalNode[params.length];
    TajoDataTypes.DataType[] paramTypes = new TajoDataTypes.DataType[params.length];

    CatalogProtos.FunctionType functionType = setFunction.isDistinct() ?
        CatalogProtos.FunctionType.DISTINCT_AGGREGATION : CatalogProtos.FunctionType.AGGREGATION;
    givenArgs[0] = visit(ctx, stack, params[0]);
    if (setFunction.getSignature().equalsIgnoreCase("count")) {
      paramTypes[0] = CatalogUtil.newSimpleDataType(TajoDataTypes.Type.ANY);
    } else {
      paramTypes[0] = givenArgs[0].getValueType();
    }

    if (!catalog.containFunction(setFunction.getSignature(), functionType, paramTypes)) {
      throw new NoSuchFunctionException(setFunction.getSignature(), paramTypes);
    }

    FunctionDesc funcDesc = catalog.getFunction(setFunction.getSignature(), functionType, paramTypes);
    if (!ctx.currentBlock.hasNode(NodeType.GROUP_BY)) {
      ctx.currentBlock.setAggregationRequire();
    }

    try {
      return new AggregationFunctionCallEval(funcDesc, (AggFunction) funcDesc.newInstance(), givenArgs);
    } catch (InternalException e) {
      throw new PlanningException(e);
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Literal Section
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public EvalNode visitDataType(Context ctx, Stack<Expr> stack, DataTypeExpr expr) throws PlanningException {
    return super.visitDataType(ctx, stack, expr);
  }

  @Override
  public EvalNode visitCastExpr(Context ctx, Stack<Expr> stack, CastExpr expr) throws PlanningException {
    EvalNode child = super.visitCastExpr(ctx, stack, expr);

    if (child.getType() == EvalType.CONST) { // if it is a casting operation for a constant value
      ConstEval constEval = (ConstEval) child; // it will be pre-computed and casted to a constant value
      return new ConstEval(DatumFactory.cast(constEval.getValue(), LogicalPlanner.convertDataType(expr.getTarget())));
    } else {
      return new CastEval(child, LogicalPlanner.convertDataType(expr.getTarget()));
    }
  }

  @Override
  public EvalNode visitLiteral(Context ctx, Stack<Expr> stack, LiteralValue expr) throws PlanningException {
    switch (expr.getValueType()) {
    case Boolean:
      return new ConstEval(DatumFactory.createBool(((BooleanLiteral) expr).isTrue()));
    case String:
      return new ConstEval(DatumFactory.createText(expr.getValue()));
    case Unsigned_Integer:
      return new ConstEval(DatumFactory.createInt4(expr.getValue()));
    case Unsigned_Large_Integer:
      return new ConstEval(DatumFactory.createInt8(expr.getValue()));
    case Unsigned_Float:
      return new ConstEval(DatumFactory.createFloat8(expr.getValue()));
    default:
      throw new RuntimeException("Unsupported type: " + expr.getValueType());
    }
  }

  @Override
  public EvalNode visitNullLiteral(Context ctx, Stack<Expr> stack, NullLiteral expr) throws PlanningException {
    return new ConstEval(NullDatum.get());
  }

  @Override
  public EvalNode visitDateLiteral(Context context, Stack<Expr> stack, DateLiteral expr) throws PlanningException {
    DateValue dateValue = expr.getDate();
    int [] dates = dateToIntArray(dateValue.getYears(), dateValue.getMonths(), dateValue.getDays());
    return new ConstEval(new DateDatum(dates[0], dates[1], dates[2]));
  }

  @Override
  public EvalNode visitTimestampLiteral(Context ctx, Stack<Expr> stack, TimestampLiteral expr)
      throws PlanningException {
    DateValue dateValue = expr.getDate();
    TimeValue timeValue = expr.getTime();

    int [] dates = dateToIntArray(dateValue.getYears(),
        dateValue.getMonths(),
        dateValue.getDays());
    int [] times = timeToIntArray(timeValue.getHours(),
        timeValue.getMinutes(),
        timeValue.getSeconds(),
        timeValue.getSecondsFraction());
    DateTime dateTime;
    if (timeValue.hasSecondsFraction()) {
      dateTime = new DateTime(dates[0], dates[1], dates[2], times[0], times[1], times[2], times[3]);
    } else {
      dateTime = new DateTime(dates[0], dates[1], dates[2], times[0], times[1], times[2]);
    }

    return new ConstEval(new TimestampDatum(dateTime));
  }

  @Override
  public EvalNode visitTimeLiteral(Context ctx, Stack<Expr> stack, TimeLiteral expr) throws PlanningException {
    TimeValue timeValue = expr.getTime();
    int [] times = timeToIntArray(timeValue.getHours(),
        timeValue.getMinutes(),
        timeValue.getSeconds(),
        timeValue.getSecondsFraction());

    TimeDatum datum;
    if (timeValue.hasSecondsFraction()) {
      datum = new TimeDatum(times[0], times[1], times[2], times[3]);
    } else {
      datum = new TimeDatum(times[0], times[1], times[2]);
    }
    return new ConstEval(datum);
  }

  public static int [] dateToIntArray(String years, String months, String days)
      throws PlanningException {
    int year = Integer.valueOf(years);
    int month = Integer.valueOf(months);
    int day = Integer.valueOf(days);

    if (!(1 <= year && year <= 9999)) {
      throw new PlanningException(String.format("Years (%d) must be between 1 and 9999 integer value", year));
    }

    if (!(1 <= month && month <= 12)) {
      throw new PlanningException(String.format("Months (%d) must be between 1 and 12 integer value", month));
    }

    if (!(1<= day && day <= 31)) {
      throw new PlanningException(String.format("Days (%d) must be between 1 and 31 integer value", day));
    }

    int [] results = new int[3];
    results[0] = year;
    results[1] = month;
    results[2] = day;

    return results;
  }

  public static int [] timeToIntArray(String hours, String minutes, String seconds, String fractionOfSecond)
      throws PlanningException {
    int hour = Integer.valueOf(hours);
    int minute = Integer.valueOf(minutes);
    int second = Integer.valueOf(seconds);
    int fraction = 0;
    if (fractionOfSecond != null) {
      fraction = Integer.valueOf(fractionOfSecond);
    }

    if (!(0 <= hour && hour <= 23)) {
      throw new PlanningException(String.format("Hours (%d) must be between 0 and 24 integer value", hour));
    }

    if (!(0 <= minute && minute <= 59)) {
      throw new PlanningException(String.format("Minutes (%d) must be between 0 and 59 integer value", minute));
    }

    if (!(0 <= second && second <= 59)) {
      throw new PlanningException(String.format("Seconds (%d) must be between 0 and 59 integer value", second));
    }

    if (fraction != 0) {
      if (!(0 <= fraction && fraction <= 999)) {
        throw new PlanningException(String.format("Seconds (%d) must be between 0 and 999 integer value", fraction));
      }
    }

    int [] results = new int[4];
    results[0] = hour;
    results[1] = minute;
    results[2] = second;
    results[3] = fraction;

    return results;
  }
}
