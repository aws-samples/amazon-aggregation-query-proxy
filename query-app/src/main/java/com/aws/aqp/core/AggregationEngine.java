// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

/**
 * Evaluates the aggregation half of a query over the push-down rows.
 * <p>
 * Contract shared by every implementation:
 * <ul>
 *   <li>{@code document} is a single JSON object; each top-level field is bound as a queryable
 *       table, so {@code {"resultSet":[...]}} makes {@code FROM resultSet} range over the rows.</li>
 *   <li>{@code sql} is the aggregation statement produced by the planner (SELECT list,
 *       GROUP BY, HAVING, ORDER BY — never LIMIT or OFFSET, which the plan applies to the
 *       output itself).</li>
 *   <li>The result is the aggregated rows as a JSON array string.</li>
 *   <li>Numbers survive exactly: DynamoDB carries up to 38 significant digits, and an engine
 *       must neither wrap integers wider than 64 bits nor round decimals through a double.</li>
 *   <li>A row lacking a referenced attribute is tolerated, not an error — items in a
 *       schemaless store do not all have the same attributes.</li>
 *   <li>Failures caused by the query or its data (bad types, unknown columns) are reported by
 *       throwing; the API layer maps them to a 400.</li>
 * </ul>
 * Implementations hold no per-query state and are safe to share across requests.
 */
public interface AggregationEngine {

    /** Evaluates {@code sql} over the document and returns the result rows as a JSON array. */
    String query(String sql, String document);
}
