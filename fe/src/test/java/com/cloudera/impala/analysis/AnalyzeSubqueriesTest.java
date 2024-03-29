// Copyright (c) 2014 Cloudera, Inc. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;

import org.junit.Test;

import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.common.AnalysisException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class AnalyzeSubqueriesTest extends AnalyzerTest {
  private static String cmpOperators[] = {"=", "!=", "<=", ">=", ">", "<"};
  @Test
  public void TestInSubqueries() throws AnalysisException {
    String colNames[] = {"bool_col", "tinyint_col", "smallint_col", "int_col",
        "bigint_col", "float_col", "double_col", "string_col", "date_string_col",
        "timestamp_col"};
    String joinOperators[] = {"inner join", "left outer join", "right outer join",
        "left semi join", "left anti join"};

    // [NOT] IN subquery predicates
    String operators[] = {"in", "not in"};
    for (String op: operators) {
      AnalyzesOk(String.format("select * from functional.alltypes where id %s " +
          "(select id from functional.alltypestiny)", op));
      // Using column and table aliases similar to the ones produced by the
      // column/table alias generators during a rewrite.
      AnalyzesOk(String.format("select id `$c$1` from functional.alltypestiny `$a$1` " +
          "where id %s (select id from functional.alltypessmall)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select id from functional.alltypestiny)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a)", op));
      AnalyzesOk(String.format("select count(*) from functional.alltypes t where " +
          "t.id %s (select id from functional.alltypestiny)", op));
      AnalyzesOk(String.format("select t.id, max(t.int_col) from " +
          "functional.alltypes t where t.int_col %s (select int_col from " +
          "functional.alltypesagg) group by t.id having count(*) < 10", op));
      AnalyzesOk(String.format("select t.bigint_col, t.string_col from " +
          "functional.alltypes t where t.id %s (select id from " +
          "functional.alltypesagg where int_col < 10) order by bigint_col", op));

      // Complex expressions
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select id + int_col from functional.alltypestiny)", op));
      AnalyzesOk(String.format("select 1 from functional.alltypes t where " +
          "t.int_col + 1 %s (select int_col - 1 from functional.alltypestiny)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where " +
          "abs(t.double_col) %s (select int_col from functional.alltypestiny)", op));
      AnalyzesOk(String.format("select NULL from functional.alltypes t where " +
          "cast(t.double_col as int) %s (select int_col from " +
          "functional.alltypestiny)", op));
      AnalyzesOk(String.format("select count(*) from functional.alltypes where id %s " +
          "(select 1 from functional.alltypestiny)", op));
      AnalyzesOk(String.format("select * from functional.alltypes where id %s " +
          "(select 1 + 1 from functional.alltypestiny group by int_col)", op));
      AnalyzesOk(String.format("select max(id) from functional.alltypes where id %s " +
          "(select max(id) from functional.alltypesagg a where a.int_col < 10) " +
          "and bool_col = false", op));

      // Subquery returns multiple columns
      AnalysisError(String.format("select * from functional.alltypestiny t where id %s " +
          "(select id, int_col from functional.alltypessmall)", op),
          "Subquery must return a single column: (SELECT id, int_col " +
          "FROM functional.alltypessmall)");
      // Subquery returns an incompatible column type
      AnalysisError(String.format("select * from functional.alltypestiny t where id %s " +
          "(select timestamp_col from functional.alltypessmall)", op),
          "Incompatible return types 'INT' and 'TIMESTAMP' of exprs 'id' and " +
          "'timestamp_col'.");

      // Different column types in the subquery predicate
      for (String col: colNames) {
        AnalyzesOk(String.format("select * from functional.alltypes t where t.%s %s " +
            "(select a.%s from functional.alltypestiny a)", col, op, col));
      }
      // Decimal in the subquery predicate
      AnalyzesOk(String.format("select * from functional.alltypes t where " +
            "t.double_col %s (select d3 from functional.decimal_tbl a)", op));
      // Varchar in the subquery predicate
      AnalyzesOk(String.format("select * from functional.alltypes t where " +
            "t.string_col %s (select cast(a.string_col as varchar(1)) from " +
            "functional.alltypestiny a)", op));

      // Subqueries with multiple predicates in the WHERE clause
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a where a.int_col < 10)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a where a.int_col > 10 and " +
          "a.tinyint_col < 5)", op));

      // Subqueries with a GROUP BY clause
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a where a.double_col < 10.1 " +
          "group by a.id)", op));

      // Subqueries with GROUP BY and HAVING clauses
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a where a.bool_col = true and " +
          "int_col < 10 group by id having count(*) < 10)", op));

      // Subqueries with a LIMIT clause
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a where id < 100 limit 10)", op));

      // Subqueries with multiple tables in the FROM clause
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a, functional.alltypessmall s " +
          "where a.int_col = s.int_col and s.bigint_col < 100 and a.tinyint_col < 10)",
          op));

      // Different join operators between the tables in subquery's FROM clause
      for (String joinOp: joinOperators) {
        AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
            "(select a.id from functional.alltypestiny a %s functional.alltypessmall " +
            "s on a.int_col = s.int_col where a.bool_col = false)", op, joinOp));
      }

      // Correlated predicates in the subquery's ON clause
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypesagg a left outer join " +
          "functional.alltypessmall s on s.int_col = t.int_col)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypesagg a left outer join " +
          "functional.alltypessmall s on s.bigint_col = a.bigint_col and " +
          "s.int_col = t.int_col)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypesagg a left outer join " +
          "functional.alltypessmall s on a.bool_col = s.bool_col and t.int_col = 1)",
          op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypesagg a left outer join " +
          "functional.alltypessmall s on ifnull(s.int_col, s.int_col + 20) = " +
          "t.int_col + t.bigint_col)", op));

      // Subqueries with inline views
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from functional.alltypestiny a, " +
          "(select * from functional.alltypessmall) s where s.int_col = a.int_col " +
          "and s.bool_col = false)", op));

      // Subqueries with inline views that contain subqueries
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from (select id from functional.alltypesagg g where " +
          "g.int_col in (select int_col from functional.alltypestiny)) a)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where t.id %s " +
          "(select a.id from (select g.* from functional.alltypesagg g where " +
          "g.int_col in (select int_col from functional.alltypestiny)) a where " +
          "a.bigint_col = 100)", op));

      // Multiple tables in the FROM clause of the outer query block
      for (String joinOp: joinOperators) {
        AnalyzesOk(String.format("select * from functional.alltypes t %s " +
            "functional.alltypessmall s on t.int_col = s.int_col where " +
            "t.tinyint_col %s (select tinyint_col from functional.alltypesagg) " +
            "and t.bool_col = false and t.bigint_col = 10", joinOp, op));
      }

      // Subqueries in WITH clause
      AnalyzesOk(String.format("with t as (select a.* from functional.alltypes a where " +
          "id %s (select id from functional.alltypestiny)) select * from t where " +
          "t.bool_col = false and t.int_col = 10", op));

      // Subqueries in WITH and WHERE clauses
      AnalyzesOk(String.format("with t as (select a.* from functional.alltypes a " +
          "where id %s (select id from functional.alltypestiny s)) select * from t " +
          "where t.int_col in (select int_col from functional.alltypessmall) and " +
          "t.bool_col = false", op));

      // Subqueries in WITH, FROM and WHERE clauses
      AnalyzesOk(String.format("with t as (select a.* from functional.alltypes a " +
          "where id %s (select id from functional.alltypestiny)) select t.* from t, " +
          "(select * from functional.alltypesagg g where g.id in " +
          "(select id from functional.alltypes)) s where s.string_col = t.string_col " +
          "and t.int_col in (select int_col from functional.alltypessmall) and " +
          "s.bool_col = false", op));

      // Correlated subqueries
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.int_col = a.int_col) " +
          "and t.bool_col = false", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.int_col + 1 = a.int_col)",
          op));
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.int_col + 1 = a.int_col + 1)",
          op));
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.int_col + a.int_col  = " +
          "a.bigint_col and a.bool_col = true)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.bool_col = false and " +
          "a.int_col < 10)", op));
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.bool_col)", op));
      AnalyzesOk(String.format("select * from functional.alltypes a where 1 %s " +
          "(select id from functional.alltypesagg s where s.int_col = a.int_col)",
          op));

      // Multiple nesting levels (uncorrelated queries)
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg where int_col %s " +
          "(select int_col from functional.alltypestiny) and bool_col = false) " +
          "and bigint_col < 1000", op, op));

      // Multiple nesting levels (correlated queries)
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where a.int_col = t.int_col " +
          "and a.tinyint_col %s (select tinyint_col from functional.alltypestiny s " +
          "where s.bigint_col = a.bigint_col))", op, op));

      // Multiple nesting levels (correlated and uncorrelated queries)
      AnalyzesOk(String.format("select * from functional.alltypes t where id %s " +
          "(select id from functional.alltypesagg a where t.int_col = a.int_col " +
          "and a.int_col %s (select int_col from functional.alltypestiny s))",
          op, op));

      // NOT ([NOT] IN predicate)
      AnalyzesOk(String.format("select * from functional.alltypes t where not (id %s " +
          "(select id from functional.alltypesagg))", op));

      // Different cmp operators in the correlation predicate
      for (String cmpOp: cmpOperators) {
        AnalyzesOk(String.format("select * from functional.alltypes t " +
          "where t.id %s (select a.id from functional.alltypesagg a where " +
          "t.int_col %s a.int_col)", op, cmpOp));
      }

      // Uncorrelated IN subquery with analytic function
      AnalyzesOk(String.format("select id, int_col, bool_col from " +
        "functional.alltypestiny t1 where int_col %s (select min(bigint_col) " +
        "over (partition by bool_col) from functional.alltypessmall t2 where " +
        "int_col < 10)", op));
    }

    // IN subquery that is equivalent to an uncorrelated EXISTS subquery
    AnalysisError("select * from functional.alltypes t where 1 in " +
        "(select int_col from functional.alltypesagg)", "Unsupported correlated " +
        "subquery: SELECT int_col FROM functional.alltypesagg");
    // Different non-equi comparison operators in the correlated predicate
    String nonEquiCmpOperators[] = {"!=", "<=", ">=", ">", "<"};
    for (String cmpOp: nonEquiCmpOperators) {
      AnalysisError(String.format("select 1 from functional.alltypes t where 1 in " +
          "(select int_col from functional.alltypesagg g where g.id %s t.id)",
          cmpOp), String.format("Unsupported correlated subquery: SELECT int_col " +
          "FROM functional.alltypesagg g WHERE g.id %s t.id", cmpOp));
    }

    // NOT IN subquery with a correlated predicate that can't be used in an equi
    // join
    AnalysisError("select 1 from functional.alltypes t where 1 not in " +
        "(select id from functional.alltypestiny g where g.id < t.id)",
        "Unsupported correlated subquery: SELECT id FROM functional.alltypestiny g " +
        "WHERE g.id < t.id");

    // Statement with a GROUP BY and a correlated IN subquery that has
    // correlated predicate that cannot be transformed into an equi-join.
    AnalysisError("select id, count(*) from functional.alltypes t " +
        "where 1 IN (select id from functional.alltypesagg g where t.int_col < " +
        "g.int_col) group by id", "Unsupported correlated subquery: SELECT id " +
        "FROM functional.alltypesagg g WHERE t.int_col < g.int_col");

    // Reference a non-existing table in the subquery
    AnalysisError("select * from functional.alltypestiny t where id in " +
        "(select id from functional.alltypessmall s left outer join p on " +
        "(s.int_col = p.int_col))", "Table does not exist: default.p");
    // Reference a non-existing column from a table in the outer scope
    AnalysisError("select * from functional.alltypestiny t where id in " +
        "(select id from functional.alltypessmall s where s.int_col = t.bad_col)",
        "couldn't resolve column reference: 't.bad_col'");

    // Referencing the same table in the inner and the outer query block
    // No explicit alias
    AnalyzesOk("select id from functional.alltypestiny where int_col in " +
        "(select int_col from functional.alltypestiny)");
    // Different alias between inner and outer block referencing the same table
    AnalyzesOk("select id from functional.alltypestiny t where int_col in " +
        "(select int_col from functional.alltypestiny p)");
    // Alias only in the outer block
    AnalyzesOk("select id from functional.alltypestiny t where int_col in " +
        "(select int_col from functional.alltypestiny)");
    // Same alias in both inner and outer block
    AnalyzesOk("select id from functional.alltypestiny t where int_col in " +
        "(select int_col from functional.alltypestiny t)");
    // Binary predicate with non-comparable operands
    AnalysisError("select * from functional.alltypes t where " +
        "(id in (select id from functional.alltypestiny)) = 'string_val'",
        "operands of type BOOLEAN and STRING are not comparable: " +
        "(id IN (SELECT id FROM functional.alltypestiny)) = 'string_val'");

    // OR with subquery predicates
    AnalysisError("select * from functional.alltypes t where t.id in " +
        "(select id from functional.alltypesagg) or t.bool_col = false",
        "Subqueries in OR predicates are not supported: t.id IN " +
        "(SELECT id FROM functional.alltypesagg) OR t.bool_col = FALSE");
    AnalysisError("select * from functional.alltypes t where not (t.id in " +
        "(select id from functional.alltypesagg) and t.int_col = 10)",
        "Subqueries in OR predicates are not supported: t.id NOT IN " +
        "(SELECT id FROM functional.alltypesagg) OR t.int_col != 10");
    AnalysisError("select * from functional.alltypes t where exists " +
        "(select * from functional.alltypesagg g where g.bool_col = false) " +
        "or t.bool_col = true", "Subqueries in OR predicates are not " +
        "supported: EXISTS (SELECT * FROM functional.alltypesagg g WHERE " +
        "g.bool_col = FALSE) OR t.bool_col = TRUE");
    AnalysisError("select * from functional.alltypes t where t.id = " +
        "(select min(id) from functional.alltypesagg g) or t.id = 10",
        "Subqueries in OR predicates are not supported: t.id = " +
        "(SELECT min(id) FROM functional.alltypesagg g) OR t.id = 10");

    // Correlated subquery with OR predicate
    AnalysisError("select * from functional.alltypes t where id in " +
        "(select id from functional.alltypesagg a where " +
        "a.int_col = t.int_col or a.bool_col = false)", "Disjunctions " +
        "with correlated predicates are not supported: a.int_col = " +
        "t.int_col OR a.bool_col = FALSE");

    AnalyzesOk("select * from functional.alltypes t where id in " +
        "(select id from functional.alltypestiny) and (bool_col = false or " +
        "int_col = 10)");

    // Correlated subqueries with GROUP BY, AGG functions or DISTINCT
    AnalysisError("select * from functional.alltypes t where t.id in " +
        "(select max(a.id) from functional.alltypesagg a where " +
        "t.int_col = a.int_col)", "Unsupported correlated subquery with grouping " +
        "and/or aggregation: SELECT max(a.id) FROM functional.alltypesagg a " +
        "WHERE t.int_col = a.int_col");
    AnalysisError("select * from functional.alltypes t where t.id in " +
        "(select a.id from functional.alltypesagg a where " +
        "t.int_col = a.int_col group by a.id)", "Unsupported correlated " +
        "subquery with grouping and/or aggregation: SELECT a.id FROM " +
        "functional.alltypesagg a WHERE t.int_col = a.int_col GROUP BY a.id");
    AnalysisError("select * from functional.alltypes t where t.id in " +
        "(select distinct a.id from functional.alltypesagg a where " +
        "a.bigint_col = t.bigint_col)", "Unsupported correlated subquery with " +
        "grouping and/or aggregation: SELECT DISTINCT a.id FROM " +
        "functional.alltypesagg a WHERE a.bigint_col = t.bigint_col");

    // NOT compound predicates with OR
    AnalyzesOk("select * from functional.alltypes t where not (" +
        "id in (select id from functional.alltypesagg) or int_col < 10)");
    AnalyzesOk("select * from functional.alltypes t where not (" +
        "t.id < 10 or not (t.int_col in (select int_col from " +
        "functional.alltypesagg) and t.bool_col = false))");

    // Multiple subquery predicates
    AnalyzesOk("select * from functional.alltypes t where id in " +
        "(select id from functional.alltypestiny where int_col = 10) and int_col in " +
        "(select int_col from functional.alltypessmall where bigint_col = 1000) and " +
        "string_col not in (select string_col from functional.alltypesagg where " +
        "tinyint_col > 10) and bool_col = false");

    // Correlated subquery with a LIMIT clause
    AnalysisError("select * from functional.alltypes t where id in " +
        "(select s.id from functional.alltypesagg s where s.int_col = t.int_col " +
        "limit 1)", "Unsupported correlated subquery with a LIMIT clause: " +
        "SELECT s.id FROM functional.alltypesagg s WHERE s.int_col = t.int_col " +
        "LIMIT 1");

    // Correlated IN with an analytic function
    AnalysisError("select id, int_col, bool_col from functional.alltypestiny t1 " +
        "where int_col in (select min(bigint_col) over (partition by bool_col) " +
        "from functional.alltypessmall t2 where t1.id < t2.id)", "Unsupported " +
        "correlated subquery with grouping and/or aggregation: SELECT " +
        "min(bigint_col) OVER (PARTITION BY bool_col) FROM " +
        "functional.alltypessmall t2 WHERE t1.id < t2.id");
  }

  @Test
  public void TestExistsSubqueries() throws AnalysisException {
    String existsOperators[] = {"exists", "not exists"};

    for (String op: existsOperators) {
      // [NOT] EXISTS predicate (correlated)
      AnalyzesOk(String.format("select * from functional.alltypes t " +
          "where %s (select * from functional.alltypestiny p where " +
          "p.id = t.id)", op));
      AnalyzesOk(String.format("select count(*) from functional.alltypes t " +
          "where %s (select * from functional.alltypestiny p where " +
          "p.int_col = t.int_col and p.bool_col = false)", op));
      AnalyzesOk(String.format("select count(*) from functional.alltypes t, " +
          "functional.alltypessmall s where s.id = t.id and %s (select * from " +
          "functional.alltypestiny a where a.int_col = t.int_col)", op));
      AnalyzesOk(String.format("select count(*) from functional.alltypes t, " +
          "functional.alltypessmall s where s.id = t.id and %s (select * from " +
          "functional.alltypestiny a where a.int_col = t.int_col and a.bool_col = " +
          "t.bool_col)", op));
      // Multiple [NOT] EXISTS predicates
      AnalyzesOk(String.format("select 1 from functional.alltypestiny t where " +
          "%s (select * from functional.alltypessmall s where s.id = t.id) and " +
          "%s (select NULL from functional.alltypesagg g where t.int_col = g.int_col)",
          op, op));
      // OR between two subqueries
      AnalysisError(String.format("select * from functional.alltypestiny t where " +
          "%s (select * from functional.alltypesagg a where a.id = t.id) or %s " +
          "(select * from functional.alltypessmall s where s.int_col = t.int_col)", op,
          op), String.format("Subqueries in OR predicates are not supported: %s " +
          "(SELECT * FROM functional.alltypesagg a WHERE a.id = t.id) OR %s (SELECT " +
          "* FROM functional.alltypessmall s WHERE s.int_col = t.int_col)",
          op.toUpperCase(), op.toUpperCase()));
      // Complex correlation predicates
      AnalyzesOk(String.format("select 1 from functional.alltypestiny t where " +
          "%s (select * from functional.alltypesagg a where a.id = t.id + 1) and " +
          "%s (select 1 from functional.alltypes s where s.int_col + s.bigint_col = " +
          "t.bigint_col + 1)", op, op));
      // Correlated predicates
      AnalyzesOk(String.format("select * from functional.alltypestiny t where " +
          "%s (select * from functional.alltypesagg g where t.int_col = g.int_col " +
          "and t.bool_col = false)", op));
      AnalyzesOk(String.format("select * from functional.alltypestiny t where " +
          "%s (select id from functional.alltypessmall s where t.tinyint_col = " +
          "s.tinyint_col and t.bool_col)", op));
      // Multiple nesting levels
      AnalyzesOk(String.format("select * from functional.alltypes t where %s " +
          "(select * from functional.alltypessmall s where t.id = s.id and %s " +
          "(select * from functional.alltypestiny g where g.int_col = s.int_col))",
          op, op));
      AnalyzesOk(String.format("select * from functional.alltypes t where %s " +
          "(select * from functional.alltypessmall s where t.id = s.id and %s " +
          "(select * from functional.alltypestiny g where g.bool_col = " +
          "s.bool_col))", op, op));
      String nullOps[] = {"is null", "is not null"};
      for (String nullOp: nullOps) {
        // Uncorrelated EXISTS subquery in an IS [NOT] NULL predicate
        AnalyzesOk(String.format("select * from functional.alltypes where %s " +
            "(select * from functional.alltypestiny) %s and id < 5", op, nullOp));
        // Correlated EXISTS subquery in an IS [NOT] NULL predicate
        AnalyzesOk(String.format("select * from functional.alltypes t where " +
            "%s (select 1 from functional.alltypestiny s where t.id = s.id) " +
            "%s and t.bool_col = false", op, nullOp));
      }
    }

    // Different non-equi comparison operators in the correlated predicate
    String nonEquiCmpOperators[] = {"!=", "<=", ">=", ">", "<"};
    for (String cmpOp: nonEquiCmpOperators) {
      AnalysisError(String.format("select * from functional.alltypes t where exists " +
          "(select * from functional.alltypesagg a where t.id %s a.id)", cmpOp),
          String.format("Unsupported correlated subquery: SELECT * FROM " +
          "functional.alltypesagg a WHERE t.id %s a.id", cmpOp));
    }
    // Uncorrelated EXISTS in a query with GROUP BY
    AnalyzesOk("select id, count(*) from functional.alltypes t " +
        "where exists (select 1 from functional.alltypestiny where id < 5) group by id");
    // Subquery with a correlated predicate that cannot be transformed into an
    // equi-join
    AnalysisError("select * from functional.alltypestiny t where " +
        "exists (select int_col + 1 from functional.alltypessmall s where " +
        "t.int_col = 10)", "Unsupported correlated subquery: SELECT int_col + 1 " +
        "FROM functional.alltypessmall s WHERE t.int_col = 10");
    // Uncorrelated EXISTS subquery
    AnalyzesOk("select * from functional.alltypestiny where exists " +
        "(select * from functional.alltypesagg where id < 10)");
    AnalyzesOk("select id from functional.alltypestiny where exists " +
        "(select id from functional.alltypessmall where bool_col = false)");
    AnalyzesOk("select 1 from functional.alltypestiny t where exists " +
        "(select 1 from functional.alltypessmall where id < 5)");
    AnalyzesOk("select 1 + 1 from functional.alltypestiny where exists " +
        "(select null from functional.alltypessmall where id != 5)");
    // Multiple nesting levels with uncorrelated EXISTS
    AnalyzesOk("select id from functional.alltypes where exists " +
        "(select id from functional.alltypestiny where int_col < 10 and exists (" +
        "select id from functional.alltypessmall where bool_col = true))");
    // Uncorrelated NOT EXISTS subquery
    AnalysisError("select * from functional.alltypestiny where not exists " +
        "(select 1 from functional.alltypessmall where bool_col = false)",
        "Unsupported uncorrelated NOT EXISTS subquery: SELECT 1 FROM " +
        "functional.alltypessmall WHERE bool_col = FALSE");

    // Subquery references an explicit alias from the outer block in the FROM
    // clause
    AnalysisError("select * from functional.alltypestiny t where " +
        "exists (select * from t)", "Table does not exist: default.t");
    // Uncorrelated subquery with no FROM clause
    AnalyzesOk("select * from functional.alltypes where exists (select 1,2)");
    // EXISTS subquery in a binary predicate
    AnalysisError("select * from functional.alltypes where " +
        "if(exists(select * from functional.alltypesagg), 1, 0) = 1",
        "IN and/or EXISTS subquery predicates are not supported in binary predicates: " +
        "if(EXISTS (SELECT * FROM functional.alltypesagg), 1, 0) = 1");
    // Correlated subquery with a LIMIT clause
    AnalysisError("select count(*) from functional.alltypes t where exists " +
        "(select 1 from functional.alltypesagg g where t.id = g.id limit 1)",
        "Unsupported correlated subquery with a LIMIT clause: " +
        "SELECT 1 FROM functional.alltypesagg g WHERE t.id = g.id LIMIT 1");

    // Uncorrelated EXISTS subquery with analytic function
    AnalyzesOk("select id, int_col, bool_col from " +
      "functional.alltypestiny t1 where exists (select min(bigint_col) " +
      "over (partition by bool_col) from functional.alltypessmall t2 where " +
      "int_col < 10)");

    // Correlated EXISTS with an analytic function
    AnalysisError("select id, int_col, bool_col from functional.alltypestiny t1 " +
        "where exists (select min(bigint_col) over (partition by bool_col) " +
        "from functional.alltypessmall t2 where t1.id = t2.id)", "Unsupported " +
        "correlated subquery with grouping and/or aggregation: SELECT " +
        "min(bigint_col) OVER (PARTITION BY bool_col) FROM " +
        "functional.alltypessmall t2 WHERE t1.id = t2.id");
  }

  @Test
  public void TestAggregateSubqueries() throws AnalysisException {
    String aggFns[] = {"count(id)", "max(id)", "min(id)", "avg(id)", "sum(id)"};
    for (String aggFn: aggFns) {
      for (String cmpOp: cmpOperators) {
        // Uncorrelated
        AnalyzesOk(String.format("select * from functional.alltypes where id %s " +
            "(select %s from functional.alltypestiny)", cmpOp, aggFn));
        AnalyzesOk(String.format("select * from functional.alltypes where " +
            "(select %s from functional.alltypestiny) %s id", aggFn, cmpOp));
        // Uncorrelated with constant expr
        AnalyzesOk(String.format("select * from functional.alltypes where 10 %s " +
            "(select %s from functional.alltypestiny)", cmpOp, aggFn));
        // Uncorrelated with complex cmp expr
        AnalyzesOk(String.format("select * from functional.alltypes where id + 10 %s " +
            "(select %s from functional.alltypestiny)", cmpOp, aggFn));
        AnalyzesOk(String.format("select * from functional.alltypes where id + 10 %s " +
            "(select %s + 1 from functional.alltypestiny)", cmpOp, aggFn));
        AnalyzesOk(String.format("select * from functional.alltypes where " +
            "(select %s + 1 from functional.alltypestiny) %s id + 10", aggFn, cmpOp));
        AnalyzesOk(String.format("select 1 from functional.alltypes where " +
            "1 + (select %s - 1 from functional.alltypestiny where bool_col = false) " +
            "%s id - 10", aggFn, cmpOp));
        // Correlated
        AnalyzesOk(String.format("select count(*) from functional.alltypes a where " +
            "id %s (select %s from functional.alltypestiny t where t.bool_col = false " +
            "and a.int_col = t.int_col) and a.bigint_col < 10", cmpOp, aggFn));
        // Correlated with constant expr
        AnalyzesOk(String.format("select count(*) from functional.alltypes a where " +
            "10 %s (select %s from functional.alltypestiny t where t.bool_col = false " +
            "and a.int_col = t.int_col) and a.bigint_col < 10", cmpOp, aggFn));

        // Correlated with complex expr
        AnalyzesOk(String.format("select count(*) from functional.alltypes a where " +
            "id - 10 %s (select %s from functional.alltypestiny t where t.bool_col = " +
            "false and a.int_col = t.int_col) and a.bigint_col < 10", cmpOp, aggFn));

        // count is not supported in select list expressions of a correlated subquery
        if (aggFn.equals("count(id)")) {
          AnalysisError(String.format("select count(*) from functional.alltypes a where " +
              "id - 10 %s (select 1 + %s from functional.alltypestiny t where " +
              "t.bool_col = false and a.int_col = t.int_col) and a.bigint_col < 10",
              cmpOp, aggFn), String.format("Aggregate function that returns non-null " +
              "on an empty input cannot be used in an expression in a " +
              "correlated subquery's select list: (SELECT 1 + %s FROM " +
              "functional.alltypestiny t WHERE t.bool_col = FALSE AND a.int_col = " +
              "t.int_col)", aggFn));
        } else {
          AnalyzesOk(String.format("select count(*) from functional.alltypes a where " +
              "id - 10 %s (select 1 + %s from functional.alltypestiny t where " +
              "t.bool_col = false and a.int_col = t.int_col) and a.bigint_col < 10",
              cmpOp, aggFn));
          AnalyzesOk(String.format("select count(*) from functional.alltypes a where " +
              "(select 1 + %s from functional.alltypestiny t where t.bool_col = false " +
              "and a.int_col = t.int_col) %s id - 10 and a.bigint_col < 10", aggFn, cmpOp));
          AnalyzesOk(String.format("select count(*) from functional.alltypes a where " +
              "1 + (select 1 + %s from functional.alltypestiny t where t.id = a.id " +
              "and t.int_col < 10) %s a.id + 10", aggFn, cmpOp));
        }
      }
    }

    for (String cmpOp: cmpOperators) {
      // Multiple tables in parent and subquery query blocks
      AnalyzesOk(String.format("select * from functional.alltypes t, " +
          "functional.alltypesagg a where a.id = t.id and t.int_col %s (" +
          "select max(g.int_col) from functional.alltypestiny g left outer join " +
          "functional.alltypessmall s on s.bigint_col = g.bigint_col where " +
          "g.bool_col = false) and t.bool_col = true", cmpOp));
      // Group by in the parent query block
      AnalyzesOk(String.format("select t.int_col, count(*) from " +
          "functional.alltypes t left outer join functional.alltypesagg g " +
          "on t.id = g.id where t.bigint_col %s (select count(*) from " +
          "functional.alltypestiny a where a.int_col < 10) and g.bool_col = false " +
          "group by t.int_col having count(*) < 100", cmpOp));
      // Multiple binary predicates
      AnalyzesOk(String.format("select * from functional.alltypes a where " +
          "int_col %s (select min(int_col) from functional.alltypesagg g where " +
          "g.bool_col = false) and int_col %s (select max(int_col) from " +
          "functional.alltypesagg g where g.bool_col = true) and a.tinyint_col = 10",
          cmpOp, cmpOp));
      // Multiple nesting levels
      AnalyzesOk(String.format("select * from functional.alltypes a where " +
          "tinyint_col %s (select count(*) from functional.alltypesagg g where " +
          "g.int_col %s (select max(int_col) from functional.alltypestiny t where " +
          "t.id = g.id) and g.id = a.id and g.bool_col = false) and a.int_col < 10",
          cmpOp, cmpOp));
      // NOT with a binary subquery predicate
      AnalyzesOk(String.format("select * from functional.alltypes a where " +
          "not (int_col %s (select max(int_col) from functional.alltypesagg g where " +
          "a.id = g.id and g.bool_col = false))", cmpOp));
      // Subquery returns a scalar (no FORM clause)
      AnalyzesOk(String.format("select id from functional.alltypestiny where id %s " +
        "(select 1)", cmpOp));
      // Incompatible comparison types
      AnalysisError(String.format("select id from functional.alltypestiny where " +
          "int_col %s (select max(timestamp_col) from functional.alltypessmall)", cmpOp),
          String.format("operands of type INT and TIMESTAMP are not comparable: " +
          "int_col %s (SELECT max(timestamp_col) FROM functional.alltypessmall)", cmpOp));
      // Distinct in the outer select block
      AnalyzesOk(String.format("select distinct id from functional.alltypes a " +
          "where 100 %s (select count(*) from functional.alltypesagg g where " +
          "a.int_col %s g.int_col) and a.bool_col = false", cmpOp, cmpOp));
    }

    // Subquery returns multiple rows
    AnalysisError("select * from functional.alltypestiny where " +
        "(select max(id) from functional.alltypes) = " +
        "(select id from functional.alltypestiny)",
        "Subquery must return a single row: " +
        "(SELECT id FROM functional.alltypestiny)");
    AnalysisError("select id from functional.alltypestiny t where int_col = " +
        "(select int_col from functional.alltypessmall limit 2)",
        "Subquery must return a single row: " +
        "(SELECT int_col FROM functional.alltypessmall LIMIT 2)");
    AnalysisError("select id from functional.alltypestiny where int_col = " +
        "(select id from functional.alltypessmall)",
        "Subquery must return a single row: " +
        "(SELECT id FROM functional.alltypessmall)");

    // Subquery returns multiple columns
    AnalysisError("select id from functional.alltypestiny where int_col = " +
        "(select id, int_col from functional.alltypessmall)",
        "Subquery must return a single row: " +
        "(SELECT id, int_col FROM functional.alltypessmall)");
    AnalysisError("select * from functional.alltypestiny where id in " +
        "(select * from (values(1,2)) as t)",
        "Subquery must return a single column: (SELECT * FROM (VALUES(1, 2)) t)");

    // Subquery returns multiple columns due to a group by clause
    AnalysisError("select id from functional.alltypestiny where int_col = " +
        "(select int_col, count(*) from functional.alltypessmall group by int_col)",
        "Subquery must return a single row: " +
        "(SELECT int_col, count(*) FROM functional.alltypessmall " +
        "GROUP BY int_col)");

    // Outer join with a table from the outer block using an explicit alias
    AnalysisError("select id from functional.alltypestiny t where int_col = " +
        "(select count(*) from functional.alltypessmall s left outer join t " +
        "on (t.id = s.id))", "Table does not exist: default.t");
    AnalysisError("select id from functional.alltypestiny t where int_col = " +
        "(select count(*) from functional.alltypessmall s right outer join t " +
        "on (t.id = s.id))", "Table does not exist: default.t");
    AnalysisError("select id from functional.alltypestiny t where int_col = " +
        "(select count(*) from functional.alltypessmall s full outer join t " +
        "on (t.id = s.id))", "Table does not exist: default.t");

    // Multiple subqueries in a binary predicate
    AnalysisError("select * from functional.alltypestiny t where " +
        "(select count(*) from functional.alltypessmall) = " +
        "(select count(*) from functional.alltypesagg)", "Multiple subqueries are not " +
        "supported in binary predicates: (SELECT count(*) FROM " +
        "functional.alltypessmall) = (SELECT count(*) FROM functional.alltypesagg)");
    AnalysisError("select * from functional.alltypestiny t where " +
        "(select max(id) from functional.alltypessmall) + " +
        "(select min(id) from functional.alltypessmall) - " +
        "(select count(id) from functional.alltypessmall) < 1000",
        "Multiple subqueries are not supported in binary predicates: (SELECT max(id) " +
        "FROM functional.alltypessmall) + (SELECT min(id) FROM " +
        "functional.alltypessmall) - (SELECT count(id) FROM functional.alltypessmall) " +
        "< 1000");

    // Comparison between invalid types
    AnalysisError("select * from functional.alltypes where " +
        "(select max(string_col) from functional.alltypesagg) = 1",
        "operands of type STRING and TINYINT are not comparable: (SELECT " +
        "max(string_col) FROM functional.alltypesagg) = 1");

    // Aggregate subquery with a LIMIT 1 clause
    AnalyzesOk("select id from functional.alltypestiny t where int_col = " +
        "(select int_col from functional.alltypessmall limit 1)");
    // Correlated aggregate suquery with correlated predicate that can't be
    // transformed into an equi-join
    AnalysisError("select id from functional.alltypestiny t where " +
        "1 < (select sum(int_col) from functional.alltypessmall s where " +
        "t.id < 10)", "Unsupported correlated subquery: SELECT sum(int_col) " +
        "FROM functional.alltypessmall s WHERE t.id < 10");
    // Aggregate subqueries in an IS [NOT] NULL predicate
    String nullOps[] = {"is null", "is not null"};
    for (String aggFn: aggFns) {
      for (String nullOp: nullOps) {
        // Uncorrelated aggregate subquery
        AnalyzesOk(String.format("select * from functional.alltypestiny where " +
            "(select %s from functional.alltypessmall where bool_col = false) " +
            "%s and int_col < 10", aggFn, nullOp));
        // Correlated aggregate subquery
        AnalyzesOk(String.format("select * from functional.alltypestiny t where " +
            "(select %s from functional.alltypessmall s where s.id = t.id " +
            "and s.bool_col = false) %s and bool_col = true", aggFn, nullOp));
      }
    }
    // Aggregate subquery with a correlated predicate that can't be transformed
    // into an equi-join in an IS NULL predicate
    AnalysisError("select 1 from functional.alltypestiny t where " +
        "(select max(id) from functional.alltypessmall s where t.id < 10) " +
        "is null", "Unsupported correlated subquery: SELECT max(id) FROM " +
        "functional.alltypessmall s WHERE t.id < 10");

    // Mathematical functions with scalar subqueries
    String mathFns[] = {"abs", "cos", "ceil", "floor"};
    for (String mathFn: mathFns) {
      for (String aggFn: aggFns) {
        String expr = aggFn.equals("count(id)") ? "" : "1 + ";
        for (String cmpOp: cmpOperators) {
          // Uncorrelated scalar subquery
          AnalyzesOk(String.format("select count(*) from functional.alltypes t where " +
              "%s((select %s %s from functional.alltypessmall where bool_col = " +
              "false)) %s 100 - t.int_col and t.bigint_col < 100", mathFn, expr, aggFn,
              cmpOp));
          // Correlated scalar subquery
          AnalyzesOk(String.format("select count(*) from functional.alltypes t where " +
              "%s((select %s %s from functional.alltypessmall s where bool_col = false " +
              "and t.id = s.id)) %s 100 - t.int_col and t.bigint_col < 100", mathFn, expr,
              aggFn, cmpOp));
        }
      }
    }

    // Conditional functions with scalar subqueries
    for (String aggFn: aggFns) {
      AnalyzesOk(String.format("select * from functional.alltypestiny t where " +
          "nullifzero((select %s from functional.alltypessmall s where " +
          "s.bool_col = false)) is null", aggFn));
      AnalyzesOk(String.format("select count(*) from functional.alltypes t where " +
          "zeroifnull((select %s from functional.alltypessmall s where t.id = s.id)) " +
          "= 0 and t.int_col < 10", aggFn));
      AnalyzesOk(String.format("select 1 from functional.alltypes t where " +
          "isnull((select %s from functional.alltypestiny s where s.bool_col = false " +
          "), 10) < 5", aggFn));
    }

    // Correlated aggregate subquery with a GROUP BY
    AnalysisError("select min(t.id) as min_id from functional.alltypestiny t " +
        "where t.int_col < (select max(s.int_col) from functional.alltypessmall s " +
        "where s.id = t.id group by s.bigint_col order by 1 limit 1)",
        "Unsupported correlated subquery with grouping and/or aggregation: " +
        "SELECT max(s.int_col) FROM functional.alltypessmall s WHERE " +
        "s.id = t.id GROUP BY s.bigint_col ORDER BY 1 ASC LIMIT 1");
    // Correlated aggregate subquery with a LIMIT clause
    AnalyzesOk("select count(*) from functional.alltypes t where " +
        "t.id = (select count(*) from functional.alltypesagg g where " +
        "g.int_col = t.int_col limit 1)");

    // Aggregate subquery with analytic function
    AnalysisError("select id, int_col, bool_col from " +
      "functional.alltypestiny t1 where int_col = (select min(bigint_col) " +
      "over (partition by bool_col) from functional.alltypessmall t2 where " +
      "int_col < 10)", "Subquery must return a single row: (SELECT " +
      "min(bigint_col) OVER (PARTITION BY bool_col) FROM " +
      "functional.alltypessmall t2 WHERE int_col < 10)");

    // Aggregate subquery with analytic function and limit 1 clause
    AnalysisError("select id, int_col, bool_col from " +
      "functional.alltypestiny t1 where int_col = (select min(bigint_col) " +
      "over (partition by bool_col) from functional.alltypessmall t2 where " +
      "t1.id = t2.id and int_col < 10 limit 1)", "Unsupported correlated " +
      "subquery with grouping and/or aggregation: SELECT min(bigint_col) " +
      "OVER (PARTITION BY bool_col) FROM functional.alltypessmall t2 WHERE " +
      "t1.id = t2.id AND int_col < 10 LIMIT 1");

    // Uncorrelated aggregate subquery with analytic function and limit 1 clause
    AnalyzesOk("select id, int_col, bool_col from " +
      "functional.alltypestiny t1 where int_col = (select min(bigint_col) " +
      "over (partition by bool_col) from functional.alltypessmall t2 where " +
      "int_col < 10 limit 1)");

    // Subquery with distinct in binary predicate
    AnalysisError("select * from functional.alltypes where int_col = " +
        "(select distinct int_col from functional.alltypesagg)", "Subquery " +
        "must return a single row: (SELECT DISTINCT int_col FROM " +
        "functional.alltypesagg)");
    AnalyzesOk("select * from functional.alltypes where int_col = " +
        "(select count(distinct int_col) from functional.alltypesagg)");
    // Multiple count aggregate functions in a correlated subquery's select list
    AnalysisError("select * from functional.alltypes t where " +
        "int_col = (select count(id) + count(int_col) - 1 from " +
        "functional.alltypesagg g where g.int_col = t.int_col)",
        "Aggregate function that returns non-null on an empty input " +
        "cannot be used in an expression in a correlated " +
        "subquery's select list: (SELECT count(id) + count(int_col) - 1 " +
        "FROM functional.alltypesagg g WHERE g.int_col = t.int_col)");

    // UDAs in aggregate subqqueries
    addTestUda("AggFn", Type.BIGINT, Type.BIGINT);
    AnalysisError("select * from functional.alltypesagg g where " +
        "(select aggfn(int_col) from functional.alltypes s where " +
        "s.id = g.id) = 10", "UDAs are not supported in the select list of " +
        "correlated subqueries: (SELECT default.aggfn(int_col) FROM " +
        "functional.alltypes s WHERE s.id = g.id)");
    AnalyzesOk("select * from functional.alltypesagg g where " +
        "(select aggfn(int_col) from functional.alltypes s where " +
        "s.bool_col = false) < 10");

    // sample, histogram, ndv, distinctpc, distinctpcsa is scalar subqueries
    String aggFnsReturningStringOnEmpty[] = {"sample(int_col)", "histogram(int_col)",
        "distinctpc(int_col)", "distinctpcsa(int_col)"};
    for (String aggFn: aggFnsReturningStringOnEmpty) {
      AnalyzesOk(String.format("select * from functional.alltypestiny t where " +
        "t.string_col = (select %s from functional.alltypesagg g where t.id = " +
        "g.id)", aggFn));
    }
  }

  @Test
  public void TestSubqueries() throws AnalysisException {
    // EXISTS, IN and aggregate subqueries
    AnalyzesOk("select * from functional.alltypes t where exists " +
        "(select * from functional.alltypesagg a where a.int_col = " +
        "t.int_col) and t.bigint_col in (select bigint_col from " +
        "functional.alltypestiny s) and t.bool_col = false and " +
        "t.int_col = (select min(int_col) from functional.alltypesagg)");
    // Nested IN with an EXISTS subquery that contains an aggregate subquery
    AnalyzesOk("select count(*) from functional.alltypes t where t.id " +
        "in (select id from functional.alltypesagg a where a.int_col = " +
        "t.int_col and exists (select * from functional.alltypestiny s " +
        "where s.bool_col = a.bool_col and s.int_col = (select min(int_col) " +
        "from functional.alltypessmall where bigint_col = 10)))");
    // Nested EXISTS with an IN subquery that has a nested aggregate subquery
    AnalyzesOk("select count(*) from functional.alltypes t where exists " +
        "(select * from functional.alltypesagg a where a.id in (select id " +
        "from functional.alltypestiny s where bool_col = false and " +
        "s.int_col < (select max(int_col) from functional.alltypessmall where " +
        "bigint_col < 100)) and a.int_col = t.int_col)");
    // Nested aggregate subqueries with EXISTS and IN subqueries
    AnalyzesOk("select count(*) from functional.alltypes t where t.int_col = " +
        "(select avg(g.int_col) * 2 from functional.alltypesagg g where g.id in " +
        "(select id from functional.alltypessmall s where exists (select " +
        "* from functional.alltypestiny a where a.int_col = s.int_col and " +
        "a.bigint_col < 10)))");

    // INSERT SELECT
    AnalyzesOk("insert into functional.alltypessmall partition (year, month) " +
        "select * from functional.alltypes where id in (select id from " +
        "functional.alltypesagg a where a.bool_col = false)");
    AnalyzesOk("insert into functional.alltypessmall partition (year, month) " +
        "select * from functional.alltypes t where int_col in (select int_col " +
        "from functional.alltypesagg a where a.id = t.id) and exists " +
        "(select * from functional.alltypestiny s where s.bigint_col = " +
        "t.bigint_col) and int_col < (select min(int_col) from functional.alltypes)");

    // CTAS with correlated subqueries
    AnalyzesOk("create table functional.test_tbl as select * from " +
        "functional.alltypes t where t.id in (select id from functional.alltypesagg " +
        "a where a.int_col = t.int_col and a.bool_col = false) and not exists " +
        "(select * from functional.alltypestiny s where s.int_col = t.int_col) " +
        "and t.bigint_col = (select count(*) from functional.alltypessmall)");

    // Predicate with a child subquery in the HAVING clause
    AnalysisError("select id, count(*) from functional.alltypestiny t group by " +
        "id having count(*) > (select count(*) from functional.alltypesagg)",
        "Subqueries are not supported in the HAVING clause.");
    AnalysisError("select id, count(*) from functional.alltypestiny t group by " +
        "id having (select count(*) from functional.alltypesagg) > 10",
        "Subqueries are not supported in the HAVING clause.");

    // Subquery in the select list
    AnalysisError("select id, (select int_col from functional.alltypestiny) " +
        "from functional.alltypestiny",
        "Subqueries are not supported in the select list.");

    // Subquery in the GROUP BY clause
    AnalysisError("select id, count(*) from functional.alltypestiny " +
        "group by (select int_col from functional.alltypestiny)",
        "Subqueries are not supported in the GROUP BY clause.");

    // Subquery in the ORDER BY clause
    AnalysisError("select id from functional.alltypestiny " +
        "order by (select int_col from functional.alltypestiny)",
        "Subqueries are not supported in the ORDER BY clause.");

    // Subquery with an inline view
    AnalyzesOk("select id from functional.alltypestiny t where exists " +
        "(select * from (select id, int_col from functional.alltypesagg) a where " +
        "a.id < 10 and a.int_col = t.int_col)");

    // Inner block references an inline view in the outer block
    AnalysisError("select id from (select * from functional.alltypestiny) t " +
        "where t.int_col = (select count(*) from t)",
        "Table does not exist: default.t");
    AnalysisError("select id from (select * from functional.alltypestiny) t " +
        "where t.int_col = (select count(*) from t) and " +
        "t.string_col in (select string_col from t)",
        "Table does not exist: default.t");
    AnalysisError("select id from (select * from functional.alltypestiny) t " +
        "where exists (select * from t, functional.alltypesagg p where " +
        "t.id = p.id)", "Table does not exist: default.t");

    // Subquery referencing a view
    AnalyzesOk("select * from functional.alltypes a where exists " +
        "(select * from functional.alltypes_view b where b.id = a.id)");
    // Same view referenced in both the inner and outer block
    AnalyzesOk("select * from functional.alltypes_view a where exists " +
        "(select * from functional.alltypes_view b where a.id = b.id)");

    // Union in the subquery
    AnalysisError("select * from functional.alltypes where exists " +
        "(select id from functional.alltypestiny union " +
        "select id from functional.alltypesagg)",
        "A subquery must contain a single select block: " +
        "(SELECT id FROM functional.alltypestiny UNION " +
        "SELECT id FROM functional.alltypesagg)");
    AnalysisError("select * from functional.alltypes where exists (values(1))",
        "A subquery must contain a single select block: (VALUES(1))");

    // Subquery in LIMIT
    AnalysisError("select * from functional.alltypes limit " +
        "(select count(*) from functional.alltypesagg)",
        "LIMIT expression must be a constant expression: " +
        "(SELECT count(*) FROM functional.alltypesagg)");

    // NOT predicates in conjunction with subqueries
    AnalyzesOk("select * from functional.alltypes t where t.id not in " +
        "(select id from functional.alltypesagg g where g.bool_col = false) " +
        "and t.string_col not like '%1%' and not (t.int_col < 5) " +
        "and not (t.int_col is null) and not (t.int_col between 5 and 10)");
    // IS NULL with an InPredicate that contains a subquery
    AnalysisError("select * from functional.alltypestiny t where (id in " +
        "(select id from functional.alltypes)) is null", "Unsupported IS NULL " +
        "predicate that contains a subquery: (id IN (SELECT id FROM " +
        "functional.alltypes)) IS NULL");
    // IS NULL with a BinaryPredicate that contains a subquery
    AnalyzesOk("select * from functional.alltypestiny where (id = " +
        "(select max(id) from functional.alltypessmall)) is null");

    // between predicates with subqueries
    AnalyzesOk("select * from functional.alltypestiny where " +
        "(select avg(id) from functional.alltypesagg where bool_col = true) " +
        "between 1 and 100 and int_col < 10");
    AnalyzesOk("select count(*) from functional.alltypestiny t where " +
        "(select count(id) from functional.alltypesagg g where t.id = g.id " +
        "and g.bigint_col < 10) between 1 and 1000");
    AnalyzesOk("select id from functional.alltypestiny where " +
        "int_col between (select min(int_col) from functional.alltypesagg where " +
        "id < 10) and 100 and bool_col = false");
    AnalyzesOk("select * from functional.alltypessmall s where " +
        "int_col between (select count(t.id) from functional.alltypestiny t where " +
        "t.int_col = s.int_col) and (select max(int_col) from " +
        "functional.alltypes a where a.id = s.id and a.bool_col = false)");
    AnalyzesOk("select * from functional.alltypessmall where " +
        "int_col between (select min(int_col) from functional.alltypestiny) and " +
        "(select max(int_col) from functional.alltypestiny) and bigint_col between " +
        "(select min(bigint_col) from functional.alltypesagg) and (select " +
        "max(bigint_col) from functional.alltypesagg)");
    AnalysisError("select * from functional.alltypestiny where (select min(id) " +
        "from functional.alltypes) between 1 and (select max(id) from " +
        "functional.alltypes)", "Comparison between subqueries is not supported " +
        "in a between predicate: (SELECT min(id) FROM functional.alltypes) BETWEEN " +
        "1 AND (SELECT max(id) FROM functional.alltypes)");
  }
}
