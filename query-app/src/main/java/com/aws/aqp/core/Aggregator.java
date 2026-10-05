// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.connectors.ExtractResult;
import com.aws.aqp.connectors.Extractor;
import com.aws.aqp.core.sql.QueryPlan;
import com.aws.aqp.core.sql.QueryPlanner;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Plans a query, runs its push-down half, then aggregates the rows.
 * <p>
 * Publishes, under {@code com.aws.aqp.core.Aggregator.*} on the admin port's /metrics:
 * {@code retrieve} and {@code aggregate} timers, and {@code rows}, {@code payloadBytes} and
 * (DynamoDB only) {@code consumedReadCapacityUnits} histograms. Request counts, overall latency
 * and error rates by type come from the resource's {@code @Timed}/{@code @ExceptionMetered}.
 */
public class Aggregator {

    private final Extractor extractor;
    private final ObjectMapper objectMapper;
    private static final QueryPlanner queryPlanner = new QueryPlanner();
    private static final Logger LOGGER = LoggerFactory.getLogger(Aggregator.class);

    private final Timer retrieveTimer;
    private final Timer aggregateTimer;
    private final Histogram rows;
    private final Histogram payloadBytes;
    private final Histogram consumedReadCapacityUnits;

    public Aggregator(Extractor extractor, MetricRegistry metrics) {
        this.extractor = extractor;
        // Reads decimals from the aggregation result as BigDecimal. By default Jackson reads them
        // as doubles, which rounds any value with more than about 16 significant digits;
        // DynamoDB numbers carry up to 38. (Large integers already become BigInteger.)
        this.objectMapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.retrieveTimer = metrics.timer(MetricRegistry.name(Aggregator.class, "retrieve"));
        this.aggregateTimer = metrics.timer(MetricRegistry.name(Aggregator.class, "aggregate"));
        this.rows = metrics.histogram(MetricRegistry.name(Aggregator.class, "rows"));
        this.payloadBytes = metrics.histogram(MetricRegistry.name(Aggregator.class, "payloadBytes"));
        this.consumedReadCapacityUnits = metrics.histogram(
                MetricRegistry.name(Aggregator.class, "consumedReadCapacityUnits"));
    }

    /**
     * Returns the response object. It is serialized once, by the JAX-RS Jackson provider.
     * Previously this returned a JSON string that the resource method then ran through
     * {@code StringEscapeUtils.unescapeJson}, which corrupted any value containing a quote,
     * backslash or newline and let a stored value inject arbitrary keys into the response.
     */
    public Response aggregate(String query) throws JsonProcessingException, InterruptedException {

        QueryPlan plan = queryPlanner.plan(query);
        LOGGER.debug("Query plan: {}", plan);

        ExtractResult extracted;
        long retrieveNanos;
        try (Timer.Context ignored = retrieveTimer.time()) {
            long start = System.nanoTime();
            extracted = extractor.execute(plan);
            retrieveNanos = System.nanoTime() - start;
        }
        rows.update(extracted.rowCount());
        payloadBytes.update(extracted.byteCount());
        if (extracted.consumedReadCapacityUnits() != null) {
            // Histograms take longs; recorded in hundredths of an RCU to keep fractional reads.
            consumedReadCapacityUnits.update(Math.round(extracted.consumedReadCapacityUnits() * 100));
        }

        String aggregatedResult;
        long aggregateNanos;
        try (Timer.Context ignored = aggregateTimer.time()) {
            long start = System.nanoTime();
            aggregatedResult = new AggregationEngine().query(plan.aggregationQuery(), extracted.document());
            aggregateNanos = System.nanoTime() - start;
        }

        // Response shape kept as it was: "response": [{"resultSet": [...rows...]}].
        ArrayNode jsonNodeResponse = objectMapper.createArrayNode();
        jsonNodeResponse.addObject().set("resultSet", plan.window((ArrayNode) objectMapper.readTree(aggregatedResult)));

        return new Response(
                new Stats(TimeUnit.NANOSECONDS.toMillis(retrieveNanos),
                        TimeUnit.NANOSECONDS.toMillis(aggregateNanos),
                        extracted.byteCount(),
                        extracted.rowCount(),
                        extracted.consumedReadCapacityUnits()),
                jsonNodeResponse);
    }

}
