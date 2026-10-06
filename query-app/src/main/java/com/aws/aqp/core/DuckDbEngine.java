// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.core.errors.InvalidQueryException;
import com.aws.aqp.core.errors.ResultTooLargeException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBStruct;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AggregationEngine} backed by DuckDB over JDBC: an embedded, columnar SQL engine.
 * <p>
 * Each query gets its own in-memory database ({@code jdbc:duckdb:}), so concurrent requests are
 * naturally isolated and everything is released when the connection closes. Rows are loaded
 * through DuckDB's appender API with a schema inferred from the rows themselves — never through
 * DuckDB's JSON reader, which parses numbers via a {@code double} and corrupts digits past the
 * 17th even into a typed DECIMAL column. The appender keeps all 38 DynamoDB digits exact.
 * <p>
 * Schema inference per attribute (schemaless rows): numbers become {@code DECIMAL(p,s)} sized to
 * the widest value seen ({@code DOUBLE}, with a precision-loss warning, only when one attribute
 * mixes magnitudes beyond 38 total digits); consistently-shaped objects become {@code STRUCT}s,
 * so nested paths ({@code m.v}) work; attributes with mixed types, or containing arrays, fall
 * back to {@code VARCHAR} holding the JSON text — {@code COUNT} works there, arithmetic fails
 * with a clear binder error. A missing attribute is {@code NULL}, which SQL aggregates skip,
 * matching the PartiQL engine's PERMISSIVE behaviour.
 * <p>
 * Statements are prepared, not just executed: only {@code prepareStatement} surfaces DuckDB's
 * real error ("Binder Error: No function matches 'sum(VARCHAR)'"); plain execution hides it
 * behind a generic message.
 * <p>
 * Known behaviour differences from {@link PartiQLEngine}, documented in the README: projected
 * missing attributes appear as {@code "x": null} rather than being omitted; {@code /} is SQL
 * float division ({@code 7/2 = 3.5}); an unaliased aggregate is named {@code count(pk)} rather
 * than {@code _1}; attributes differing only in letter case collide (DuckDB identifiers are
 * case-insensitive) and are rejected.
 */
public class DuckDbEngine implements AggregationEngine {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final long MIN_MEMORY_LIMIT = 256L * 1024 * 1024;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DuckDbEngine.class);

    private final long memoryLimitBytes;

    /**
     * @param maxResultBytes the proxy's push-down byte budget; DuckDB's own memory ceiling is
     *                       derived from it so one query cannot exhaust the container
     */
    public DuckDbEngine(long maxResultBytes) {
        this(Math.max(MIN_MEMORY_LIMIT, 4 * maxResultBytes), true);
    }

    /** Test seam: an exact engine memory limit, below the floor the public constructor applies. */
    static DuckDbEngine withMemoryLimitBytes(long memoryLimitBytes) {
        return new DuckDbEngine(memoryLimitBytes, true);
    }

    private DuckDbEngine(long memoryLimitBytes, boolean marker) {
        this.memoryLimitBytes = memoryLimitBytes;
    }

    @Override
    public String query(String sql, String document) {
        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Expected a single JSON object as the input document.");
        }

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET memory_limit='" + (memoryLimitBytes / (1024 * 1024)) + "MB'");
            }
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                loadTable(connection, field.getKey(), field.getValue());
            }
            try (PreparedStatement prepared = connection.prepareStatement(sql);
                 ResultSet resultSet = prepared.executeQuery()) {
                return JSON.writeValueAsString(toJsonRows(resultSet));
            }
        } catch (SQLException e) {
            throw translate(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- schema inference + load

    private enum Kind { VARCHAR, JSON_TEXT, BOOLEAN, DECIMAL, DOUBLE, STRUCT }

    /** Union of everything seen for one attribute across all rows. */
    private static final class Column {
        boolean sawString, sawBool, sawNumber, sawStruct, sawOther;
        int maxIntDigits, maxScale;
        int decidedScale;
        final Map<String, Column> fields = new LinkedHashMap<>();
        /** DuckDB identifiers are case-insensitive; two spellings of one name must not collide. */
        final Map<String, String> fieldCase = new HashMap<>();
        Kind kind;
        String sqlType;
    }

    private void loadTable(Connection connection, String name, JsonNode value) throws SQLException {
        if (!value.isArray()) {
            throw new IllegalArgumentException(String.format(
                    "The DuckDB engine expects every top-level document field to be an array of rows; '%s' is %s.",
                    name, value.getNodeType()));
        }
        Column table = new Column();
        for (JsonNode row : value) {
            if (!row.isObject()) {
                throw new IllegalArgumentException(String.format(
                        "The DuckDB engine expects rows to be objects; '%s' contains %s.", name, row.getNodeType()));
            }
            mergeStructFields(table, (ObjectNode) row);
        }
        if (table.fields.isEmpty()) {
            // No rows or no attributes: a single dummy column keeps CREATE TABLE valid and
            // COUNT(*) correct over however many (empty) rows there are.
            Column dummy = new Column();
            table.fields.put("__empty", dummy);
        }
        table.fields.values().forEach(DuckDbEngine::decide);

        StringBuilder create = new StringBuilder("CREATE TABLE ").append(quote(name)).append(" (");
        boolean first = true;
        for (var e : table.fields.entrySet()) {
            if (!first) {
                create.append(", ");
            }
            first = false;
            create.append(quote(e.getKey())).append(' ').append(e.getValue().sqlType);
        }
        create.append(')');
        try (Statement statement = connection.createStatement()) {
            statement.execute(create.toString());
        }

        try (DuckDBAppender appender = connection.unwrap(DuckDBConnection.class).createAppender(name)) {
            for (JsonNode row : value) {
                appender.beginRow();
                for (var e : table.fields.entrySet()) {
                    append(appender, e.getValue(), row.get(e.getKey()));
                }
                appender.endRow();
            }
        }
    }

    private static void mergeStructFields(Column struct, ObjectNode object) {
        var fields = object.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String lower = field.getKey().toLowerCase(java.util.Locale.ROOT);
            String existing = struct.fieldCase.putIfAbsent(lower, field.getKey());
            if (existing != null && !existing.equals(field.getKey())) {
                throw new InvalidQueryException(String.format(
                        "Attributes '%s' and '%s' differ only in letter case; DuckDB identifiers are "
                                + "case-insensitive, so the DuckDB engine cannot load them. Use the PARTIQL engine.",
                        existing, field.getKey()));
            }
            merge(struct.fields.computeIfAbsent(field.getKey(), k -> new Column()), field.getValue());
        }
    }

    private static void merge(Column column, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            column.sawString = true;
        } else if (value.isBoolean()) {
            column.sawBool = true;
        } else if (value.isNumber()) {
            column.sawNumber = true;
            BigDecimal decimal = normalize(value.decimalValue());
            column.maxIntDigits = Math.max(column.maxIntDigits, decimal.precision() - decimal.scale());
            column.maxScale = Math.max(column.maxScale, decimal.scale());
        } else if (value.isObject()) {
            column.sawStruct = true;
            mergeStructFields(column, (ObjectNode) value);
        } else {
            column.sawOther = true; // arrays and anything else: JSON text fallback
        }
    }

    private static void decide(Column column) {
        int kinds = (column.sawString ? 1 : 0) + (column.sawBool ? 1 : 0)
                + (column.sawNumber ? 1 : 0) + (column.sawStruct ? 1 : 0);
        if (column.sawOther || kinds > 1) {
            column.kind = Kind.JSON_TEXT;
            column.sqlType = "VARCHAR";
        } else if (column.sawStruct) {
            column.kind = Kind.STRUCT;
            StringBuilder sb = new StringBuilder("STRUCT(");
            boolean first = true;
            for (var e : column.fields.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                decide(e.getValue());
                sb.append(quote(e.getKey())).append(' ').append(e.getValue().sqlType);
            }
            column.sqlType = sb.append(')').toString();
        } else if (column.sawNumber) {
            // Always at least one integer digit: the appender rejects DECIMAL(p,p) columns,
            // and the spare digit costs nothing.
            int precision = Math.max(1, column.maxIntDigits) + column.maxScale;
            if (precision <= 38) {
                column.kind = Kind.DECIMAL;
                column.decidedScale = column.maxScale;
                column.sqlType = "DECIMAL(" + precision + "," + column.maxScale + ")";
            } else {
                // One attribute spans more than 38 total digits across rows (for example 1E30
                // and 1E-30 together). No DECIMAL holds both; precision loss is unavoidable.
                LOGGER.warn("Attribute needs {} digits of precision; loading as DOUBLE with precision loss",
                        precision);
                column.kind = Kind.DOUBLE;
                column.sqlType = "DOUBLE";
            }
        } else if (column.sawBool) {
            column.kind = Kind.BOOLEAN;
            column.sqlType = "BOOLEAN";
        } else {
            column.kind = Kind.VARCHAR; // strings, or never seen (all rows lack it: all NULL)
            column.sqlType = "VARCHAR";
        }
    }

    private static void append(DuckDBAppender appender, Column column, JsonNode value) throws SQLException {
        if (value == null || value.isNull()) {
            appender.appendNull();
            return;
        }
        switch (column.kind) {
            case DECIMAL:
                // Rescaled to the column's scale, which is the maximum seen, so this is always
                // exact; the appender rejects values whose scale differs from the column's.
                appender.append(normalize(value.decimalValue()).setScale(column.decidedScale));
                break;
            case DOUBLE:
                appender.append(value.decimalValue().doubleValue());
                break;
            case BOOLEAN:
                appender.append(value.asBoolean());
                break;
            case STRUCT:
                appender.beginStruct();
                for (var e : column.fields.entrySet()) {
                    append(appender, e.getValue(), value.get(e.getKey()));
                }
                appender.endStruct();
                break;
            case JSON_TEXT:
                try {
                    appender.append(JSON.writeValueAsString(value));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                break;
            default:
                appender.append(value.asText());
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        // A negative scale (1E+2) is exact at scale 0 and keeps precision arithmetic simple.
        return value.scale() < 0 ? value.setScale(0) : value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------------- result reading

    private static ArrayNode toJsonRows(ResultSet resultSet) throws SQLException {
        ArrayNode rows = JSON.createArrayNode();
        ResultSetMetaData metaData = resultSet.getMetaData();
        while (resultSet.next()) {
            ObjectNode row = rows.addObject();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                row.set(metaData.getColumnLabel(i), toJsonValue(resultSet.getObject(i)));
            }
        }
        return rows;
    }

    private static JsonNode toJsonValue(Object value) throws SQLException {
        if (value == null) {
            return JSON.nullNode();
        }
        if (value instanceof BigDecimal) {
            return JSON.getNodeFactory().numberNode((BigDecimal) value);
        }
        if (value instanceof java.math.BigInteger) {
            // HUGEINT: exact.
            return JSON.getNodeFactory().numberNode(new BigDecimal((java.math.BigInteger) value));
        }
        if (value instanceof Double || value instanceof Float) {
            return JSON.getNodeFactory().numberNode(((Number) value).doubleValue());
        }
        if (value instanceof Number) {
            return JSON.getNodeFactory().numberNode(((Number) value).longValue());
        }
        if (value instanceof Boolean) {
            return JSON.getNodeFactory().booleanNode((Boolean) value);
        }
        if (value instanceof String) {
            return JSON.getNodeFactory().textNode((String) value);
        }
        if (value instanceof DuckDBStruct) {
            ObjectNode object = JSON.createObjectNode();
            for (var e : ((DuckDBStruct) value).getMap().entrySet()) {
                object.set(e.getKey(), toJsonValue(e.getValue()));
            }
            return object;
        }
        if (value instanceof java.sql.Array) {
            ArrayNode array = JSON.createArrayNode();
            for (Object element : (Object[]) ((java.sql.Array) value).getArray()) {
                array.add(toJsonValue(element));
            }
            return array;
        }
        // Dates, intervals, blobs: cannot come from JSON input; render as text rather than fail.
        return JSON.getNodeFactory().textNode(String.valueOf(value));
    }

    // -------------------------------------------------------------------------- error mapping

    /**
     * DuckDB reports everything as SQLException; split the caller's faults from the engine's.
     * Order matters: the real OOM text is "Out of Memory Error: failed to pin block ..."
     * (captured from a live engine), which the caller-error pattern would not match.
     */
    static RuntimeException translate(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String firstLine = message.split("\n")[0].trim();
        if (message.contains("Out of Memory")) {
            return new ResultTooLargeException(
                    "The aggregation exceeded the engine's memory budget. Narrow the WHERE clause.");
        }
        if (firstLine.matches("(?s)(Binder|Parser|Catalog|Conversion|Invalid Input|Out of Range) Error.*")) {
            return new InvalidQueryException("The aggregation step could not evaluate the query: " + firstLine);
        }
        return new RuntimeException("DuckDB could not evaluate the aggregation: " + firstLine, e);
    }
}
