// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.core.errors.QueryTimeoutException;
import com.aws.aqp.core.errors.ResultTooLargeException;
import com.aws.aqp.core.sql.Dialect;
import com.aws.aqp.core.sql.QueryPlan;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Reads a CQL result set from Amazon Keyspaces, page by page.
 * <p>
 * All per-query state lives in a {@link PageCollector} created inside
 * {@link #execute(QueryPlan)}. This extractor is registered as a singleton and is therefore
 * shared by every concurrent request, so nothing about one query may be stored on the
 * instance.
 * <p>
 * The session is owned by the application, which closes it on shutdown.
 */
public class CassandraExtractor extends Extractor {

    private final CqlSession cqlSession;
    private final long maxRows;
    private final long maxResultBytes;
    private final long queryTimeoutSeconds;
    /** Exact decimals: row values are re-serialized when keys are unquoted, and must not round. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraExtractor.class);

    public CassandraExtractor(AppConfiguration appConfiguration, CqlSession cqlSession) {
        this.cqlSession = cqlSession;
        this.maxRows = appConfiguration.getMaxRows();
        this.maxResultBytes = appConfiguration.getMaxResultBytes();
        this.queryTimeoutSeconds = appConfiguration.getQueryTimeoutSeconds();
    }

    /**
     * Accumulates the rows of one query. Pages are delivered on driver threads, so the row
     * queue and counters are concurrent and the terminal state is published through the latch.
     */
    static final class PageCollector {

        final Queue<String> rows = new ConcurrentLinkedQueue<>();
        final AtomicLong rowCount = new AtomicLong();
        final AtomicLong byteCount = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        private final long maxRows;
        private final long maxResultBytes;
        private final boolean unquoteKeys;
        private final long rowLimit;
        private volatile boolean cancelled;

        PageCollector(long maxRows, long maxResultBytes) {
            this(maxRows, maxResultBytes, false);
        }

        PageCollector(long maxRows, long maxResultBytes, boolean unquoteKeys) {
            this(maxRows, maxResultBytes, unquoteKeys, Long.MAX_VALUE);
        }

        /**
         * @param unquoteKeys strip the quotes Cassandra and Keyspaces keep on case-sensitive
         *                    column names in SELECT JSON output ({@code {"\"Amount\"": 5}}),
         *                    so the aggregation step can find the column. Costs a parse per
         *                    row, so enabled only when the query projects such a column.
         */
        /**
         * @param rowLimit stop after this many rows: the query's own LIMIT, when it can be
         *                 applied while reading (see {@link QueryPlan#rowLimit()}). The store is
         *                 also sent the LIMIT; this makes the bound independent of that.
         */
        PageCollector(long maxRows, long maxResultBytes, boolean unquoteKeys, long rowLimit) {
            this.maxRows = maxRows;
            this.maxResultBytes = maxResultBytes;
            this.unquoteKeys = unquoteKeys;
            this.rowLimit = rowLimit;
        }

        /**
         * Stops paging: pages that arrive afterwards are dropped and no further page is
         * requested. Called when the caller has already been answered — on timeout or interrupt
         * — so an abandoned query stops consuming Keyspaces capacity and heap.
         */
        void cancel() {
            cancelled = true;
            done.countDown();
        }

        void accept(AsyncResultSet resultSet, Throwable error) {
            // Any exception escaping this callback would be swallowed by the CompletionStage
            // and the latch would never be released, so everything is funnelled into `failure`.
            try {
                if (cancelled) {
                    return;
                }
                if (error != null) {
                    fail(error);
                    return;
                }
                for (var row : resultSet.currentPage()) {
                    String json = row.getString(0);
                    if (json == null) {
                        continue;
                    }
                    if (unquoteKeys) {
                        json = unquoteColumnKeys(json);
                    }
                    rows.add(json);
                    long rowsSoFar = rowCount.incrementAndGet();
                    long bytesSoFar = byteCount.addAndGet(json.getBytes(StandardCharsets.UTF_8).length);
                    if (rowsSoFar > maxRows) {
                        fail(new ResultTooLargeException(String.format(
                                "Query matched more than the configured maximum of %d rows. "
                                        + "Narrow the WHERE clause or raise maxRows.", maxRows)));
                        return;
                    }
                    if (bytesSoFar > maxResultBytes) {
                        fail(new ResultTooLargeException(String.format(
                                "Result set exceeded the configured maximum of %d bytes. "
                                        + "Narrow the WHERE clause or raise maxResultBytes.", maxResultBytes)));
                        return;
                    }
                    if (rowsSoFar >= rowLimit) {
                        done.countDown();
                        return;
                    }
                }
                if (resultSet.hasMorePages() && !cancelled) {
                    resultSet.fetchNextPage().whenComplete(this::accept);
                } else {
                    done.countDown();
                }
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void fail(Throwable t) {
            failure.compareAndSet(null, t);
            done.countDown();
        }

        void rethrowIfFailed() {
            Throwable t = failure.get();
            if (t == null) {
                return;
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * Rewrites top-level keys of the form {@code "\"Name\""} to {@code "Name"}, undoing CQL's
     * doubled-quote escaping inside them. Other keys are left as they are.
     */
    static String unquoteColumnKeys(String json) {
        try {
            JsonNode parsed = JSON.readTree(json);
            if (!(parsed instanceof ObjectNode)) {
                return json;
            }
            ObjectNode unquoted = JSON.createObjectNode();
            parsed.fields().forEachRemaining(field -> {
                String key = field.getKey();
                if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
                    key = key.substring(1, key.length() - 1).replace("\"\"", "\"");
                }
                unquoted.set(key, field.getValue());
            });
            return JSON.writeValueAsString(unquoted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Amazon Keyspaces returned a row that is not valid JSON", e);
        }
    }

    /**
     * The table's partition key columns, rendered for CQL, from the driver's schema metadata —
     * or empty if the table is not in the metadata (then the caller falls back to {@code *}).
     */
    List<String> partitionKey(QueryPlan plan) {
        List<String> parts = plan.sourceParts();
        Optional<CqlIdentifier> keyspace = parts.size() == 2
                ? Optional.of(CqlIdentifier.fromCql(parts.get(0)))
                : cqlSession.getKeyspace();
        if (keyspace.isEmpty() || parts.isEmpty() || parts.size() > 2) {
            return List.of();
        }
        CqlIdentifier table = CqlIdentifier.fromCql(parts.get(parts.size() - 1));
        return cqlSession.getMetadata().getKeyspace(keyspace.get())
                .flatMap(k -> k.getTable(table))
                .map(t -> t.getPartitionKey().stream()
                        .map(column -> column.getName().asCql(true))
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    @Override
    public ExtractResult execute(QueryPlan plan) throws InterruptedException {
        // A query that references no column (COUNT(*)) needs only something present on every
        // row. The partition key is; fetching every column instead could trip maxResultBytes
        // long before maxRows on a table with large rows.
        List<String> projection = plan.columns();
        if (plan.needsNoColumns()) {
            projection = partitionKey(plan);
            if (projection.isEmpty()) {
                LOGGER.debug("No schema metadata for {}; projecting all columns", plan.sourceParts());
            }
        }
        String statement = projection.isEmpty()
                ? plan.pushDownStatement(Dialect.CQL)
                : plan.pushDownStatement(Dialect.CQL, projection);
        boolean projectsQuotedColumn = projection.stream().anyMatch(c -> c.startsWith("\""));
        PageCollector collector = new PageCollector(maxRows, maxResultBytes, projectsQuotedColumn,
                plan.rowLimit().orElse(Long.MAX_VALUE));
        LOGGER.debug("Push-down statement: {}", statement);

        cqlSession.executeAsync(statement).whenComplete(collector::accept);

        // A timeout previously fell through and returned whichever rows had arrived, reported
        // as a complete result. Partial data must be an error, not a silent wrong answer.
        boolean completed;
        try {
            completed = collector.done.await(queryTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            collector.cancel();
            throw e;
        }
        if (!completed) {
            // Without this the driver kept fetching pages for a request that had already been
            // answered, and clients retrying on 504 stacked up abandoned scans.
            collector.cancel();
            throw new QueryTimeoutException(String.format(
                    "Amazon Keyspaces did not return a complete result set within %d seconds.", queryTimeoutSeconds));
        }
        collector.rethrowIfFailed();

        LOGGER.debug("Retrieved {} rows ({} bytes) from Amazon Keyspaces",
                collector.rowCount.get(), collector.byteCount.get());

        return new ExtractResult(
                String.format("{\"resultSet\":[%s]}", String.join(",", collector.rows)),
                collector.rowCount.get(), collector.byteCount.get(), null);
    }
}
