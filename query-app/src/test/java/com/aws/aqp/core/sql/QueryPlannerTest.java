// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.sql;

import com.aws.aqp.core.IonEngine;
import com.aws.aqp.core.errors.InvalidQueryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPlannerTest {

    private final QueryPlanner planner = new QueryPlanner();
    private final ObjectMapper mapper = new ObjectMapper();

    private String pushDown(String query) {
        return planner.plan(query).pushDownStatement(Dialect.DYNAMODB_PARTIQL);
    }

    private String aggregation(String query) {
        return planner.plan(query).aggregationQuery();
    }

    /** Runs the plan's aggregation half over the given rows and returns the result rows. */
    /** Runs the plan's aggregation half and output window, as Aggregator does. */
    private JsonNode aggregate(String query, String rowsJson) throws Exception {
        QueryPlan plan = planner.plan(query);
        String result = new IonEngine().query(plan.aggregationQuery(), "{\"resultSet\":" + rowsJson + "}");
        return plan.window((com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(result));
    }

    /** Each case reproduces a defect of the regex-based QueryTransformer this replaced. */
    @Nested
    class RegressionsFromTheRegexTransformer {

        @Test
        void tableNameContainingJsonIsNotMangled() {
            // Was: SELECT  id FROM _events WHERE pk = 'a'
            assertEquals("SELECT id FROM json_events WHERE pk = 'a'",
                    pushDown("select count(id) as c FROM json_events WHERE pk = 'a'"));
        }

        @Test
        void limitIsNotDuplicated() {
            // Was: SELECT json pk FROM t LIMIT 10 LIMIT 10 (a syntax error)
            // Was: SELECT json pk FROM t LIMIT 10 LIMIT 10. LIMIT is now applied to the output
            // by the plan, so it appears in neither the push-down (an aggregate) nor PartiQL.
            QueryPlan plan = planner.plan("select count(pk) as c FROM t LIMIT 10");
            assertFalse(plan.pushDownStatement(Dialect.CQL).contains("LIMIT"), plan::toString);
            assertFalse(plan.aggregationQuery().contains("LIMIT"), plan::toString);
        }

        @Test
        void tableNameIsNotSubstringReplacedIntoColumns() {
            // Was: select count(id) as c, resultSet_type FROM resultSet GROUP BY resultSet_type
            String query = "select count(id) as c, orders_type FROM orders GROUP BY orders_type";
            assertEquals("SELECT id, orders_type FROM orders", pushDown(query));
            assertEquals("SELECT count(id) as c, orders_type FROM resultSet GROUP BY orders_type",
                    aggregation(query));
        }

        @Test
        void singleLetterTableNameDoesNotCorruptKeywords() {
            // Was: selecresultSet counresultSet(pk) as c FROM resultSet ...
            String aggregation = aggregation("select count(pk) as c FROM t where pk = 'A,B'");
            assertEquals("SELECT count(pk) as c FROM resultSet", aggregation);
        }

        @Test
        void lowercaseWhereContentsDoNotLeakIntoTheProjection() {
            // Was: SELECT json B',pk FROM t where pk = 'A,B' — split("WHERE", CASE_INSENSITIVE)
            // is a case-sensitive split with limit 2.
            assertEquals("SELECT pk FROM t WHERE pk = 'A,B'",
                    pushDown("select count(pk) as c FROM t where pk = 'A,B'"));
        }

        @Test
        void nestedFunctionCallsProduceBalancedSql() {
            // Was: SELECT json cast(amount as int FROM t WHERE pk='x'
            assertEquals("SELECT amount FROM t WHERE pk='x'",
                    pushDown("select sum(cast(amount as int)) as t FROM t WHERE pk='x'"));
        }

        @Test
        void keywordsInsideLiteralsAreNotClauseBoundaries() {
            String query = "select count(pk) as c FROM t WHERE note = 'x GROUP BY y, LIMIT 5'";
            assertEquals("SELECT pk FROM t WHERE note = 'x GROUP BY y, LIMIT 5'", pushDown(query));
            assertFalse(aggregation(query).contains("GROUP BY"), aggregation(query));
        }

        @Test
        void inListsWithCommasArePassedThrough() {
            assertEquals("SELECT pk FROM testTable WHERE pk in ('Record1','Record2','Record3')",
                    pushDown("select count(pk) as CNT FROM testTable where pk in ('Record1','Record2','Record3')"));
        }
    }

    @Nested
    class Projection {

        /**
         * The regex transformer only projected columns that appeared in the SELECT list, so a
         * GROUP BY key that was not selected arrived missing and every row fell into one group.
         */
        @Test
        void projectsGroupByKeysThatAreNotSelected() {
            assertEquals("SELECT amount, zip FROM t",
                    pushDown("select sum(amount) as total FROM t GROUP BY zip"));
        }

        @Test
        void projectsColumnsUsedOnlyInHavingAndOrderBy() {
            assertEquals("SELECT zip, amount, qty FROM t",
                    pushDown("select zip FROM t GROUP BY zip HAVING SUM(amount) > 5 ORDER BY MAX(qty)"));
        }

        @Test
        void projectsEveryOperandOfAnExpression() {
            assertEquals("SELECT price, qty FROM t WHERE pk='x'",
                    pushDown("select sum(price*qty) as rev FROM t WHERE pk='x'"));
        }

        @Test
        void deduplicatesRepeatedColumns() {
            assertEquals("SELECT a, b FROM t",
                    pushDown("select count(a) as c, sum(a) as s, max(b) as m FROM t"));
        }

        @Test
        void countStarFetchesWholeRows() {
            assertEquals("SELECT * FROM t", pushDown("select count(*) as c FROM t"));
        }

        @Test
        void projectsTheRootOfANestedPath() {
            assertEquals("SELECT address FROM t",
                    pushDown("select count(address.city) as c FROM t"));
        }

        @Test
        void requotesQuotedAndIrregularColumnNames() {
            assertEquals("SELECT \"Order Total\", \"Zip\" FROM t",
                    pushDown("select sum(\"Order Total\") as t, \"Zip\" FROM t GROUP BY \"Zip\""));
        }

        @Test
        void doesNotProjectGroupByAliases() {
            assertEquals("SELECT amount, zip FROM t",
                    pushDown("select z, sum(amount) as total FROM t GROUP BY zip AS z"));
        }
    }

    @Nested
    class PushDown {

        @Test
        void passesQuotedAndQualifiedTableNamesThrough() {
            assertEquals("SELECT pk FROM \"my-table\" WHERE pk='x'",
                    pushDown("select count(pk) as c FROM \"my-table\" WHERE pk='x'"));
            assertEquals("SELECT pk FROM \"orders\".\"by_zip\"",
                    pushDown("select count(pk) as c FROM \"orders\".\"by_zip\""));
            assertEquals("SELECT pk FROM ks.tbl", pushDown("select count(pk) as c FROM ks.tbl"));
        }

        @Test
        void passesDialectSpecificPredicatesThroughVerbatim() {
            assertEquals("SELECT pk FROM t WHERE pk = 'a' AND begins_with(sk, 'ORDER#')",
                    pushDown("select count(pk) as c FROM t WHERE pk = 'a' AND begins_with(sk, 'ORDER#')"));
        }

        @Test
        void rendersCqlWithJsonProjectionAndKeepsPushDownOnlyClauses() {
            QueryPlan plan = planner.plan("SELECT count(x) AS c FROM ks.tbl WHERE pk = 1 "
                    + "PER PARTITION LIMIT 2 LIMIT 5 ALLOW FILTERING");
            assertEquals("SELECT JSON x FROM ks.tbl WHERE pk = 1 PER PARTITION LIMIT 2 ALLOW FILTERING",
                    plan.pushDownStatement(Dialect.CQL));
            assertFalse(plan.aggregationQuery().contains("LIMIT"), plan.aggregationQuery());
            assertFalse(plan.aggregationQuery().contains("FILTERING"), plan.aggregationQuery());
        }

        /**
         * A CQL {@code //} comment used to pass the lexer. The WHERE text is passed through
         * verbatim, so the comment swallowed what the proxy appends (here ALLOW FILTERING).
         * Rejected now, together with the other comment forms.
         */
        @Test
        void rejectsCqlLineComments() {
            InvalidQueryException e = assertThrows(InvalidQueryException.class, () -> planner.plan(
                    "SELECT COUNT(pk) AS c FROM ks.t WHERE x = 1 // note ALLOW FILTERING"));
            assertTrue(e.getMessage().contains("comments"), e::getMessage);
        }

        /** CQL dollar-quoted strings may contain quotes; they used to be rejected as unterminated. */
        @Test
        void passesDollarQuotedStringsThrough() {
            QueryPlan plan = planner.plan("SELECT COUNT(pk) AS c FROM ks.t WHERE note = $$it's // fine; really$$ ALLOW FILTERING");
            assertEquals("SELECT JSON pk FROM ks.t WHERE note = $$it's // fine; really$$ ALLOW FILTERING",
                    plan.pushDownStatement(Dialect.CQL));
        }

        @Test
        void keepsSlashesInsideStringLiterals() {
            assertEquals("SELECT pk FROM t WHERE url = 'https://example.com/a'",
                    pushDown("select count(pk) as c FROM t WHERE url = 'https://example.com/a'"));
        }

        @Test
        void toleratesATrailingSemicolon() {
            assertEquals("SELECT pk FROM t", pushDown("select count(pk) as c FROM t;"));
        }
    }

    /**
     * LIMIT used to be pushed down to the data store, where it caps the rows read rather than the
     * groups returned — so SUM ... GROUP BY ... LIMIT 2 summed two arbitrary rows.
     */
    @Nested
    class AggregationSemantics {

        private static final String ROWS = "["
                + "{\"zip\":\"Z1\",\"amount\":10},{\"zip\":\"Z1\",\"amount\":10},"
                + "{\"zip\":\"Z2\",\"amount\":5},{\"zip\":\"Z3\",\"amount\":1}]";

        @Test
        void limitAppliesToGroupsNotToInputRows() throws Exception {
            QueryPlan plan = planner.plan("select zip, sum(amount) as t FROM sales GROUP BY zip LIMIT 2");
            assertFalse(plan.pushDownStatement(Dialect.DYNAMODB_PARTIQL).contains("LIMIT"), plan.pushDownStatement(Dialect.DYNAMODB_PARTIQL));

            JsonNode rows = aggregate("select zip, sum(amount) as t FROM sales GROUP BY zip LIMIT 2", ROWS);
            assertEquals(2, rows.size());
        }

        /**
         * PartiQL applies LIMIT before aggregating when there is no GROUP BY: over 1..5 it
         * returned SUM 3 for LIMIT 2 and COUNT 1 for LIMIT 1. The plan applies LIMIT to the
         * output, where an ungrouped aggregate is a single row.
         */
        @Test
        void limitDoesNotTruncateTheRowsOfAnUngroupedAggregate() throws Exception {
            String rows = "[{\"x\":1},{\"x\":2},{\"x\":3},{\"x\":4},{\"x\":5}]";
            assertEquals(15, aggregate("select sum(x) as s FROM t LIMIT 2", rows).get(0).get("s").asInt());
            assertEquals(5, aggregate("select count(*) as n FROM t LIMIT 1", rows).get(0).get("n").asInt());
            assertEquals(0, aggregate("select count(*) as n FROM t LIMIT 0", rows).size());
        }

        @Test
        void offsetAndLimitWindowTheOrderedGroups() throws Exception {
            JsonNode rows = aggregate(
                    "select zip, sum(amount) as t FROM sales GROUP BY zip ORDER BY zip LIMIT 1 OFFSET 1", ROWS);
            assertEquals(1, rows.size(), rows::toString);
            assertEquals("Z2", rows.get(0).get("zip").asText(), rows::toString);
        }

        @Test
        void groupsByAColumnThatIsNotSelected() throws Exception {
            JsonNode rows = aggregate("select sum(amount) as t FROM sales GROUP BY zip", ROWS);
            assertEquals(3, rows.size(), rows::toString);
        }

        @Test
        void countsDuplicateRows() throws Exception {
            JsonNode rows = aggregate("select sum(amount) as total FROM sales WHERE zip = 'Z1'",
                    "[{\"amount\":10},{\"amount\":10},{\"amount\":10}]");
            assertEquals(30, rows.get(0).get("total").asInt());
        }

        @Test
        void evaluatesOverTheBoundRows() throws Exception {
            String result = new IonEngine().query(
                    planner.plan("select count(pk) as CNT FROM t").aggregationQuery(),
                    "{\"resultSet\":[{\"pk\":\"a\"},{\"pk\":\"b\"}]}");
            assertEquals(2, mapper.readTree(result).get(0).get("CNT").asInt());
        }

        @Test
        void doesNotCorruptDataValuesContainingUnderscoreOne() throws Exception {
            JsonNode rows = aggregate("select pk FROM t", "[{\"pk\":\"SKU_1\"}]");
            assertEquals("SKU_1", rows.get(0).get("pk").asText());
        }

        @Test
        void havingAndOrderByWork() throws Exception {
            JsonNode rows = aggregate(
                    "select zip, sum(amount) as t FROM sales GROUP BY zip HAVING SUM(amount) > 2 ORDER BY zip DESC",
                    ROWS);
            assertEquals(2, rows.size(), rows::toString);
            assertEquals("Z2", rows.get(0).get("zip").asText());
        }
    }

    @Nested
    class Rejections {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "select count(pk) as c FROM t o WHERE o.pk='x'        | table aliases",
                "select count(pk) as c FROM a, b                      | joins",
                "select count(pk) as c FROM a JOIN b ON a.k = b.k     | Joins",
                "select (select max(x) from t) as m FROM t            | Subqueries",
                "select count(pk) as c FROM t UNION select 1 FROM t   | Set operations",
                "select zip, sum(amount) as total FROM t GROUP BY zip ORDER BY total | SELECT alias",
                "select count(pk) as c FROM t -- comment              | comments",
                "select count(pk) as c FROM t /* c */                 | comments",
                "select count(pk) as c FROM t WHERE x = 1 // note     | comments",
                "select count(pk) as c FROM t WHERE x = $$never closed | Unterminated",
                "select count(pk) as c FROM t WHERE pk = 'open        | Unterminated",
                "select count(pk) as c FROM t WHERE                   | empty",
                "select count(pk) as c WHERE pk='x' FROM t            | out of order",
                "select count(pk) as c                                | FROM clause",
                "select count(pk as c FROM t                          | parentheses",
                "select count(pk) as c FROM t; DROP TABLE t           | single statement",
                "DELETE FROM t WHERE pk='x'                           | read-only",
                "select x FROM t LIMIT 1 + 1                          | integer literal",
                "select x FROM t LIMIT 5 OFFSET ?                     | integer literal",
        })
        void rejectsWithAnActionableMessage(String query, String expectedFragment) {
            InvalidQueryException e = assertThrows(InvalidQueryException.class, () -> planner.plan(query));
            assertTrue(e.getMessage().contains(expectedFragment.trim()),
                    () -> "message for [" + query + "] was: " + e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void rejectsEmptyInput(String query) {
            assertThrows(InvalidQueryException.class, () -> planner.plan(query));
        }
    }

    /**
     * A LIMIT is pushed to the store only when the first N rows it returns are exactly the
     * answer. It used to be pushed never, so a plain SELECT ... LIMIT 10 read the whole table.
     */
    @Nested
    class RowLimit {

        @Test
        void pushesTheLimitOfAPlainProjection() {
            QueryPlan plan = planner.plan("SELECT order_id, amount FROM ks.orders WHERE customer = 'c1' LIMIT 10");
            assertEquals(java.util.OptionalLong.of(10), plan.rowLimit());
            assertEquals("SELECT JSON order_id, amount FROM ks.orders WHERE customer = 'c1' LIMIT 10",
                    plan.pushDownStatement(Dialect.CQL));
            // DynamoDB PartiQL has no LIMIT; its extractor caps while paging instead.
            assertEquals("SELECT order_id, amount FROM ks.orders WHERE customer = 'c1'",
                    plan.pushDownStatement(Dialect.DYNAMODB_PARTIQL));
        }

        @Test
        void pushesSelectStarLimit() {
            assertEquals(java.util.OptionalLong.of(5), planner.plan("SELECT * FROM t LIMIT 5").rowLimit());
        }

        @Test
        void keepsCqlClauseOrder() {
            assertEquals("SELECT JSON x FROM ks.t WHERE pk = 1 PER PARTITION LIMIT 2 LIMIT 5 ALLOW FILTERING",
                    planner.plan("SELECT x FROM ks.t WHERE pk = 1 PER PARTITION LIMIT 2 LIMIT 5 ALLOW FILTERING")
                            .pushDownStatement(Dialect.CQL));
        }

        /** Each of these would give a wrong answer if the LIMIT capped the rows read. */
        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT COUNT(*) AS c FROM t LIMIT 1",
                "SELECT COUNT(x) AS c FROM t LIMIT 1",
                "SELECT SUM(x) AS s FROM t LIMIT 1",
                "SELECT zip, MAX(x) AS m FROM t GROUP BY zip LIMIT 1",
                "SELECT zip FROM t GROUP BY zip LIMIT 1",
                "SELECT x FROM t ORDER BY x LIMIT 1",
                "SELECT DISTINCT x FROM t LIMIT 1",
                "SELECT x FROM t LIMIT 1 OFFSET 5",
                "SELECT x FROM t LIMIT 0",
        })
        void doesNotPushALimitThatCouldChangeTheAnswer(String query) {
            QueryPlan plan = planner.plan(query);
            assertTrue(plan.rowLimit().isEmpty(), () -> "pushed LIMIT for: " + query);
            assertFalse(plan.pushDownStatement(Dialect.CQL).contains("LIMIT"), plan::toString);
        }
    }

    /**
     * COUNT(*) references no column. The extractor projects the table key in that case rather
     * than fetching every attribute; the plan must tell "no columns" apart from SELECT *.
     */
    @Nested
    class ColumnNeeds {

        @Test
        void countStarNeedsNoColumns() {
            assertTrue(planner.plan("SELECT COUNT(*) AS c FROM t WHERE pk = 'a'").needsNoColumns());
        }

        @Test
        void selectStarNeedsAllColumns() {
            QueryPlan plan = planner.plan("SELECT * FROM t LIMIT 5");
            assertFalse(plan.needsNoColumns());
            assertEquals("SELECT * FROM t", plan.pushDownStatement(Dialect.DYNAMODB_PARTIQL));
        }

        @Test
        void referencedColumnsAreNeeded() {
            assertFalse(planner.plan("SELECT SUM(x) AS s FROM t").needsNoColumns());
        }

        @Test
        void exposesTheSourcePartsAsWritten() {
            assertEquals(List.of("ks", "tbl"), planner.plan("SELECT COUNT(*) AS c FROM ks.tbl").sourceParts());
            assertEquals(List.of("\"orders\"", "\"by_zip\""),
                    planner.plan("SELECT COUNT(*) AS c FROM \"orders\".\"by_zip\"").sourceParts());
        }
    }

    /**
     * Regression for the shared static Matcher in the old transformer: roughly 4% of concurrent
     * results were built from another thread's table name, and 8% threw.
     */
    @Test
    void isSafeToCallConcurrently() throws Exception {
        int threads = 8;
        int iterations = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wrong = new AtomicInteger();
        Map<String, Integer> observed = new ConcurrentHashMap<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                executor.submit(() -> {
                    String query = String.format("select count(col%d) as c FROM tbl%d WHERE pk='x'", id, id);
                    String expected = String.format("SELECT col%d FROM tbl%d WHERE pk='x'", id, id);
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        String actual;
                        try {
                            actual = planner.plan(query).pushDownStatement(Dialect.DYNAMODB_PARTIQL);
                        } catch (Throwable e) {
                            actual = "threw " + e;
                        }
                        if (!actual.equals(expected)) {
                            wrong.incrementAndGet();
                            observed.merge(actual, 1, Integer::sum);
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(120, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, wrong.get(), () -> "concurrent planning went wrong: " + observed);
    }

    @Test
    void exposesProjectedColumns() {
        assertEquals(List.of("amount", "zip"),
                planner.plan("select sum(amount) as total FROM t GROUP BY zip").columns());
    }
}
