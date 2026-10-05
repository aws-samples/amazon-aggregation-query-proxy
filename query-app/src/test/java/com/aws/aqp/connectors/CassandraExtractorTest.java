// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.core.errors.ResultTooLargeException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the per-query collector. Uses JDK dynamic proxies rather than a mocking framework so no
 * test dependency is added; only the handful of methods the collector calls are implemented.
 */
class CassandraExtractorTest {

    private static Row row(String json) {
        return (Row) Proxy.newProxyInstance(
                CassandraExtractorTest.class.getClassLoader(),
                new Class<?>[]{Row.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName()) && args != null && args.length == 1) {
                        return json;
                    }
                    if ("toString".equals(method.getName())) {
                        return "Row[" + json + "]";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AsyncResultSet lastPage(String... jsonRows) {
        List<Row> rows = Arrays.stream(jsonRows).map(CassandraExtractorTest::row)
                .collect(Collectors.toList());
        return (AsyncResultSet) Proxy.newProxyInstance(
                CassandraExtractorTest.class.getClassLoader(),
                new Class<?>[]{AsyncResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "currentPage":
                            return rows;
                        case "hasMorePages":
                            return false;
                        case "toString":
                            return "AsyncResultSet" + rows;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    /** A page that says more follow; fetchNextPage() hands back {@code next} and counts calls. */
    private static AsyncResultSet pageWithMore(CompletableFuture<AsyncResultSet> next, AtomicInteger fetches,
                                               String... jsonRows) {
        List<Row> rows = Arrays.stream(jsonRows).map(CassandraExtractorTest::row).collect(Collectors.toList());
        return (AsyncResultSet) Proxy.newProxyInstance(
                CassandraExtractorTest.class.getClassLoader(),
                new Class<?>[]{AsyncResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "currentPage":
                            return rows;
                        case "hasMorePages":
                            return true;
                        case "fetchNextPage":
                            fetches.incrementAndGet();
                            return next;
                        case "toString":
                            return "AsyncResultSet(more)" + rows;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    /**
     * After the caller has been answered with a timeout, paging must stop. It used to carry on
     * fetching and buffering pages for a request nobody was waiting for, so clients retrying on
     * 504 stacked up background scans.
     */
    @Test
    void stopsFetchingPagesOnceCancelled() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(1000, 1 << 20);
        AtomicInteger fetches = new AtomicInteger();
        CompletableFuture<AsyncResultSet> secondPage = new CompletableFuture<>();

        collector.accept(pageWithMore(secondPage, fetches, "{\"a\":1}"), null);
        assertEquals(1, fetches.get(), "first page should request the second");

        collector.cancel();
        // The in-flight page arrives after cancellation, and itself claims more pages follow.
        collector.accept(pageWithMore(new CompletableFuture<>(), fetches, "{\"a\":2}"), null);

        assertEquals(1, fetches.get(), "kept fetching after cancellation");
        assertEquals(1, collector.rows.size(), "kept buffering rows after cancellation");
    }

    /** Stops at a pushed-down LIMIT: no rows past it, and no further page requested. */
    @Test
    void stopsAtTheRowLimit() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(1000, 1 << 20, false, 2);
        AtomicInteger fetches = new AtomicInteger();

        collector.accept(pageWithMore(new CompletableFuture<>(), fetches, "{\"a\":1}", "{\"a\":2}", "{\"a\":3}"), null);

        assertTrue(collector.done.await(1, TimeUnit.SECONDS));
        assertEquals(2, collector.rows.size());
        assertEquals(0, fetches.get(), "requested another page after the limit");
    }

    /**
     * Cassandra and Keyspaces return a case-sensitive column in SELECT JSON under a key that
     * still carries its quotes ({"\"Amount\"": 5}), so the aggregation step could not find it.
     */
    @Test
    void unquotesCaseSensitiveColumnKeysWhenAsked() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(100, 1 << 20, true);

        collector.accept(lastPage("{\"\\\"Amount\\\"\": 5, \"pk\": 1, \"\\\"Odd\\\"\\\"Name\\\"\": 2}"), null);

        assertTrue(collector.done.await(1, TimeUnit.SECONDS));
        JsonNode row = new ObjectMapper().readTree(collector.rows.peek());
        assertEquals(5, row.get("Amount").asInt(), row::toString);
        assertEquals(1, row.get("pk").asInt(), row::toString);
        assertEquals(2, row.get("Odd\"Name").asInt(), row::toString);
    }

    /**
     * Two queries in flight at once must not see each other's rows. The extractor previously kept
     * the row map and the latch on the shared singleton instance and cleared the map at the start
     * of every execute(), so a second request wiped the first request's rows.
     */
    @Test
    void collectorsAreIndependent() throws Exception {
        CassandraExtractor.PageCollector first = new CassandraExtractor.PageCollector(100, 1 << 20);
        CassandraExtractor.PageCollector second = new CassandraExtractor.PageCollector(100, 1 << 20);

        first.accept(lastPage("{\"a\":1}"), null);
        second.accept(lastPage("{\"b\":2}", "{\"b\":3}"), null);

        assertTrue(first.done.await(1, TimeUnit.SECONDS));
        assertTrue(second.done.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("{\"a\":1}"), new ArrayList<>(first.rows));
        assertEquals(List.of("{\"b\":2}", "{\"b\":3}"), new ArrayList<>(second.rows));
    }

    /** Identical rows must all be kept; they contribute to COUNT and SUM. */
    @Test
    void keepsDuplicateRows() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(100, 1 << 20);

        collector.accept(lastPage("{\"amount\":10}", "{\"amount\":10}", "{\"amount\":10}"), null);

        assertTrue(collector.done.await(1, TimeUnit.SECONDS));
        assertEquals(3, collector.rows.size());
        assertEquals(3, collector.rowCount.get());
    }

    /**
     * A driver error must release the latch and then be rethrown. It previously threw from inside
     * the CompletionStage callback, where the exception was swallowed and the latch was never
     * counted down, so the caller blocked for the full 360s timeout and then received whichever
     * rows had arrived, reported as a complete result.
     */
    @Test
    void propagatesDriverErrorsWithoutHanging() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(100, 1 << 20);
        IllegalStateException driverError = new IllegalStateException("connection reset");

        collector.accept(null, driverError);

        assertTrue(collector.done.await(1, TimeUnit.SECONDS), "latch was not released on error");
        RuntimeException thrown = assertThrows(RuntimeException.class, collector::rethrowIfFailed);
        assertSame(driverError, thrown);
    }

    @Test
    void enforcesTheRowBudget() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(2, 1 << 20);

        collector.accept(lastPage("{\"a\":1}", "{\"a\":2}", "{\"a\":3}"), null);

        assertTrue(collector.done.await(1, TimeUnit.SECONDS));
        assertThrows(ResultTooLargeException.class, collector::rethrowIfFailed);
    }

    @Test
    void enforcesTheByteBudget() throws Exception {
        CassandraExtractor.PageCollector collector = new CassandraExtractor.PageCollector(1000, 8);

        collector.accept(lastPage("{\"a\":\"0123456789\"}"), null);

        assertTrue(collector.done.await(1, TimeUnit.SECONDS));
        assertThrows(ResultTooLargeException.class, collector::rethrowIfFailed);
    }

    /**
     * Guards against reintroducing per-query state on the shared singleton. Any mutable
     * collection or latch field here would be shared by every concurrent request.
     */
    @Test
    void holdsNoPerQueryStateOnTheInstance() {
        for (Field field : CassandraExtractor.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            assertFalse(
                    Queue.class.isAssignableFrom(type)
                            || Map.class.isAssignableFrom(type)
                            || java.util.Collection.class.isAssignableFrom(type)
                            || CountDownLatch.class.isAssignableFrom(type),
                    () -> "field '" + field.getName() + "' holds per-query state on a shared "
                            + "singleton; keep it local to execute()");
        }
    }
}
