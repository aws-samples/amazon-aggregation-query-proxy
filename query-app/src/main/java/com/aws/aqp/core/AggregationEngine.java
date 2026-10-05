// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.partiql.ast.Statement;
import org.partiql.eval.Mode;
import org.partiql.eval.compiler.PartiQLCompiler;
import org.partiql.parser.PartiQLParser;
import org.partiql.planner.PartiQLPlanner;
import org.partiql.spi.catalog.Catalog;
import org.partiql.spi.catalog.Name;
import org.partiql.spi.catalog.Session;
import org.partiql.spi.catalog.Table;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;
import org.partiql.spi.value.Field;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Evaluates the aggregation half of a query with PartiQL (the 1.x engine: parse, plan against a
 * catalog, compile, execute).
 * <p>
 * Each top-level field of the input document becomes a table in an in-memory catalog, so
 * {@code {"resultSet":[...]}} makes {@code FROM resultSet} range over the rows. Rows are
 * converted straight between JSON and PartiQL {@code Datum} values — the Ion detour of the old
 * engine is gone, and with it its two defects: integers wider than 64 bits no longer wrap
 * (they become exact decimals at conversion), and evaluation runs in PERMISSIVE mode, so an
 * item that lacks a referenced attribute is skipped rather than failing the whole query —
 * DynamoDB items are schemaless, and the old engine errored on the first such row. Genuine type
 * mismatches (SUM over a string) still raise {@code PRuntimeException}, which the exception
 * mapper turns into a 400.
 */
public class AggregationEngine {

    private static final ObjectMapper JSON = new ObjectMapper()
            // Exact decimals: DynamoDB numbers carry up to 38 significant digits.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String CATALOG_NAME = "aqp";

    /** Evaluates {@code sql} over the document and returns the result rows as a JSON array. */
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

        Catalog.Builder catalog = Catalog.builder().name(CATALOG_NAME);
        root.fields().forEachRemaining(field ->
                catalog.define(Table.standard(Name.of(field.getKey()), toDatum(field.getValue()))));
        Session session = Session.builder()
                .identity(CATALOG_NAME)
                .catalog(CATALOG_NAME)
                .catalogs(catalog.build())
                .build();

        Statement statement = PartiQLParser.standard().parse(sql).statements.get(0);
        var plan = PartiQLPlanner.standard().plan(statement, session).getPlan();
        Datum result = PartiQLCompiler.standard().prepare(plan, Mode.PERMISSIVE()).execute();

        try {
            return JSON.writeValueAsString(toJson(result));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Datum toDatum(JsonNode node) {
        if (node == null || node.isNull()) {
            return Datum.nullValue();
        }
        if (node.isTextual()) {
            return Datum.string(node.asText());
        }
        if (node.isBoolean()) {
            return Datum.bool(node.asBoolean());
        }
        if (node.isInt()) {
            return Datum.integer(node.intValue());
        }
        if (node.isLong()) {
            return Datum.bigint(node.longValue());
        }
        if (node.isNumber()) {
            // BigInteger and all decimals. Exact: integers wider than 64 bits become decimals
            // with scale 0 instead of wrapping, and fractions keep every digit.
            BigDecimal value = node.decimalValue();
            if (value.scale() < 0) {
                value = value.setScale(0);
            }
            return Datum.decimal(value, Math.max(value.precision(), value.scale()), value.scale());
        }
        if (node.isArray()) {
            List<Datum> elements = new ArrayList<>(node.size());
            node.forEach(element -> elements.add(toDatum(element)));
            // A top-level JSON array is a collection of rows: a bag, so SFW iterates it.
            return Datum.bag(elements);
        }
        if (node.isObject()) {
            List<Field> fields = new ArrayList<>(node.size());
            node.fields().forEachRemaining(e -> fields.add(Field.of(e.getKey(), toDatum(e.getValue()))));
            return Datum.struct(fields);
        }
        throw new IllegalArgumentException("Unsupported JSON node type: " + node.getNodeType());
    }

    static JsonNode toJson(Datum datum) {
        if (datum.isNull() || datum.isMissing()) {
            return NullNode.getInstance();
        }
        switch (datum.getType().code()) {
            case PType.BOOL:
                return BooleanNode.valueOf(datum.getBoolean());
            case PType.TINYINT:
            case PType.SMALLINT:
            case PType.INTEGER:
                return IntNode.valueOf(datum.getInt());
            case PType.BIGINT:
                return LongNode.valueOf(datum.getLong());
            case PType.DECIMAL:
            case PType.NUMERIC:
                return DecimalNode.valueOf(datum.getBigDecimal());
            case PType.REAL:
            case PType.DOUBLE:
                return DoubleNode.valueOf(datum.getDouble());
            case PType.CHAR:
            case PType.VARCHAR:
            case PType.STRING:
                return TextNode.valueOf(datum.getString());
            case PType.ROW:
            case PType.STRUCT: {
                ObjectNode object = JSON.createObjectNode();
                for (Iterator<Field> it = datum.getFields(); it.hasNext(); ) {
                    Field field = it.next();
                    object.set(field.getName(), toJson(field.getValue()));
                }
                return object;
            }
            case PType.ARRAY:
            case PType.BAG: {
                ArrayNode array = JSON.createArrayNode();
                for (Datum element : datum) {
                    array.add(toJson(element));
                }
                return array;
            }
            default:
                // Dates, times, blobs etc. cannot be produced by JSON input; represent any
                // engine-produced value of such a type as text rather than failing the response.
                return TextNode.valueOf(String.valueOf(datum));
        }
    }
}
