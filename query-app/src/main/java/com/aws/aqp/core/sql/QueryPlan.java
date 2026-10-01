// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.sql;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.OptionalLong;

/**
 * An aggregation query split into the part the data store runs and the part the proxy runs.
 * <p>
 * Push-down: a plain projection with the caller's FROM and WHERE text passed through verbatim,
 * so dialect-specific predicates ({@code begins_with}, {@code token()}, {@code ALLOW FILTERING})
 * reach the store untouched. Aggregation: SELECT list, GROUP BY, HAVING and ORDER BY, run by
 * PartiQL over the push-down rows; then OFFSET and LIMIT, applied by {@link #window}.
 */
public final class QueryPlan {

    private final List<String> columns;
    private final boolean allColumns;
    private final String source;
    private final List<String> sourceParts;
    private final String where;
    private final String perPartitionLimit;
    private final boolean allowFiltering;
    private final Long rowLimit;
    private final String aggregationQuery;
    private final Long outputLimit;
    private final long outputOffset;

    QueryPlan(List<String> columns, boolean allColumns, String source, List<String> sourceParts, String where,
              String perPartitionLimit, boolean allowFiltering, Long rowLimit, String aggregationQuery,
              Long outputLimit, long outputOffset) {
        this.columns = List.copyOf(columns);
        this.allColumns = allColumns;
        this.source = source;
        this.sourceParts = List.copyOf(sourceParts);
        this.where = where;
        this.perPartitionLimit = perPartitionLimit;
        this.allowFiltering = allowFiltering;
        this.rowLimit = rowLimit;
        this.aggregationQuery = aggregationQuery;
        this.outputLimit = outputLimit;
        this.outputOffset = outputOffset;
    }

    /**
     * Applies the query's OFFSET and LIMIT to the aggregated rows.
     * <p>
     * Done here rather than by PartiQL, which applies LIMIT to the input rows before
     * aggregating when there is no GROUP BY: SUM(x) ... LIMIT 2 returned the sum of two rows,
     * COUNT(*) ... LIMIT 1 returned 1. Applied to the output, LIMIT means the same thing for
     * every query shape: at most N result rows.
     */
    public ArrayNode window(ArrayNode rows) {
        if (outputOffset == 0 && (outputLimit == null || outputLimit >= rows.size())) {
            return rows;
        }
        ArrayNode windowed = rows.arrayNode();
        long end = outputLimit == null ? rows.size() : Math.min(rows.size(), outputOffset + outputLimit);
        for (long i = outputOffset; i < end; i++) {
            windowed.add(rows.get((int) i));
        }
        return windowed;
    }

    /** Renders the push-down statement with the plan's own projection. */
    public String pushDownStatement(Dialect dialect) {
        return pushDownStatement(dialect, allColumns || columns.isEmpty() ? List.of("*") : columns);
    }

    /**
     * Renders the push-down statement with the given projection — used when the extractor
     * substitutes key columns for a query that references none ({@link #needsNoColumns()}).
     */
    public String pushDownStatement(Dialect dialect, List<String> projection) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (dialect == Dialect.CQL) {
            // So each row arrives as a single JSON document in column 0.
            sb.append("JSON ");
        }
        sb.append(String.join(", ", projection));
        sb.append(" FROM ").append(source);
        if (where != null) {
            sb.append(" WHERE ").append(where);
        }
        // CQL clause order: ... PER PARTITION LIMIT, LIMIT, ALLOW FILTERING.
        if (perPartitionLimit != null) {
            sb.append(" PER PARTITION LIMIT ").append(perPartitionLimit);
        }
        if (rowLimit != null && dialect == Dialect.CQL) {
            sb.append(" LIMIT ").append(rowLimit);
        }
        if (allowFiltering) {
            sb.append(" ALLOW FILTERING");
        }
        return sb.toString();
    }

    /** The PartiQL statement evaluated over the push-down rows, which are bound as {@code resultSet}. */
    public String aggregationQuery() {
        return aggregationQuery;
    }

    /** Columns the query references, rendered as identifiers. Empty if it references none. */
    public List<String> columns() {
        return columns;
    }

    /**
     * True when the query references no column and does not ask for all of them — for example
     * {@code SELECT COUNT(*)}. Any column present on every row would do; an extractor that knows
     * the table's key should project just that rather than {@code *}, which fetches every
     * attribute of every row only to count them.
     */
    public boolean needsNoColumns() {
        return !allColumns && columns.isEmpty();
    }

    /**
     * The FROM clause's identifier parts as written, quotes included: {@code ks}, {@code tbl}, or
     * {@code "orders"}, {@code "by_zip"} for a DynamoDB index.
     */
    public List<String> sourceParts() {
        return sourceParts;
    }

    /**
     * Maximum rows the store needs to return, when that is known from the query. Set only for a
     * LIMIT on a query that neither aggregates, groups, sorts, de-duplicates nor offsets — then
     * the first N rows the store returns are exactly the answer. Otherwise a LIMIT caps the
     * aggregated output and every matching row must be read.
     */
    public OptionalLong rowLimit() {
        return rowLimit == null ? OptionalLong.empty() : OptionalLong.of(rowLimit);
    }

    @Override
    public String toString() {
        return "QueryPlan{pushDown=" + pushDownStatement(Dialect.DYNAMODB_PARTIQL)
                + ", rowLimit=" + rowLimit + ", aggregation=" + aggregationQuery + "}";
    }
}
