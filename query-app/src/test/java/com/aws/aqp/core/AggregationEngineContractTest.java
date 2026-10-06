// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The behaviour every {@link AggregationEngine} must share, run against each implementation.
 * Engine-specific behaviour (documented deltas) lives in the per-engine test classes.
 */
class AggregationEngineContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final String ROWS = "{\"resultSet\":["
            + "{\"zip\":\"Z1\",\"amount\":10,\"pk\":\"a\"},{\"zip\":\"Z1\",\"amount\":10,\"pk\":\"b\"},"
            + "{\"zip\":\"Z2\",\"amount\":5,\"pk\":\"c\"},{\"zip\":\"Z3\",\"amount\":1,\"pk\":\"d\"}]}";

    static Stream<AggregationEngine> engines() {
        return Stream.of(new PartiQLEngine(), new DuckDbEngine(64L * 1024 * 1024));
    }

    private static JsonNode rows(AggregationEngine engine, String sql, String document) throws Exception {
        return MAPPER.readTree(engine.query(sql, document));
    }

    private static BigDecimal single(AggregationEngine engine, String sql, String rowsJson, String column)
            throws Exception {
        return rows(engine, sql, "{\"resultSet\":" + rowsJson + "}").get(0).get(column).decimalValue();
    }

    @ParameterizedTest
    @MethodSource("engines")
    void aggregatesWithGroupByHavingAndOrderBy(AggregationEngine engine) throws Exception {
        JsonNode out = rows(engine, "SELECT zip, SUM(amount) AS t FROM resultSet GROUP BY zip "
                + "HAVING SUM(amount) > 2 ORDER BY zip DESC", ROWS);
        assertEquals(2, out.size(), out::toString);
        assertEquals("Z2", out.get(0).get("zip").asText());
        assertEquals(5, out.get(0).get("t").asInt());
        assertEquals(20, out.get(1).get("t").asInt());
    }

    @ParameterizedTest
    @MethodSource("engines")
    void ordersByASelectAlias(AggregationEngine engine) throws Exception {
        JsonNode out = rows(engine,
                "SELECT zip, SUM(amount) AS t FROM resultSet GROUP BY zip ORDER BY t DESC", ROWS);
        assertEquals("Z1", out.get(0).get("zip").asText(), out::toString);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void groupsByAnUnselectedColumn(AggregationEngine engine) throws Exception {
        assertEquals(3, rows(engine, "SELECT SUM(amount) AS t FROM resultSet GROUP BY zip", ROWS).size());
    }

    @ParameterizedTest
    @MethodSource("engines")
    void countsStarAndDistinct(AggregationEngine engine) throws Exception {
        assertEquals(4, rows(engine, "SELECT COUNT(*) AS n FROM resultSet", ROWS).get(0).get("n").asInt());
        assertEquals(3, rows(engine, "SELECT COUNT(DISTINCT zip) AS n FROM resultSet", ROWS)
                .get(0).get("n").asInt());
    }

    @ParameterizedTest
    @MethodSource("engines")
    void countsAnEmptyResultSet(AggregationEngine engine) throws Exception {
        assertEquals(0, rows(engine, "SELECT COUNT(*) AS n FROM resultSet", "{\"resultSet\":[]}")
                .get(0).get("n").asInt());
    }

    @ParameterizedTest
    @MethodSource("engines")
    void sumsIntegersBeyond64BitsExactly(AggregationEngine engine) throws Exception {
        BigDecimal sum = single(engine, "SELECT SUM(x) AS s FROM resultSet",
                "[{\"x\":99999999999999999999999999999999999999},{\"x\":1}]", "s");
        assertEquals(0, new BigDecimal("99999999999999999999999999999999999999").add(BigDecimal.ONE)
                .compareTo(sum), sum::toPlainString);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void sumsLongsPastTheLongRangeExactly(AggregationEngine engine) throws Exception {
        BigDecimal sum = single(engine, "SELECT SUM(x) AS s FROM resultSet",
                "[{\"x\":9000000000000000000},{\"x\":9000000000000000000}]", "s");
        assertEquals(0, new BigDecimal("18000000000000000000").compareTo(sum), sum::toPlainString);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void keepsHighPrecisionDecimalsExactly(AggregationEngine engine) throws Exception {
        BigDecimal max = single(engine, "SELECT MAX(x) AS m FROM resultSet",
                "[{\"x\":0.12345678901234567890123},{\"x\":0.1}]", "m");
        assertEquals(0, new BigDecimal("0.12345678901234567890123").compareTo(max), max::toPlainString);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void sumsNestedStructFieldsExactly(AggregationEngine engine) throws Exception {
        BigDecimal sum = single(engine, "SELECT SUM(m.v) AS s FROM resultSet",
                "[{\"m\":{\"v\":12345678901234567890}},{\"m\":{\"v\":1}}]", "s");
        assertEquals(0, new BigDecimal("12345678901234567891").compareTo(sum), sum::toPlainString);
    }

    /** Rows in a schemaless store do not all share attributes; absence must not fail queries. */
    @ParameterizedTest
    @MethodSource("engines")
    void toleratesRowsMissingTheAttribute(AggregationEngine engine) throws Exception {
        String rows = "[{\"x\":1,\"g\":\"A\"},{\"g\":\"A\"},{\"x\":2,\"g\":\"B\"}]";
        assertEquals(0, BigDecimal.valueOf(3).compareTo(
                single(engine, "SELECT SUM(x) AS s FROM resultSet", rows, "s")));
        JsonNode grouped = rows(engine, "SELECT g, COUNT(x) AS c FROM resultSet GROUP BY g ORDER BY g",
                "{\"resultSet\":" + rows + "}");
        assertEquals(1, grouped.get(0).get("c").asInt());
        assertEquals(1, grouped.get(1).get("c").asInt());
    }

    @ParameterizedTest
    @MethodSource("engines")
    void handlesCaseSensitiveColumnNames(AggregationEngine engine) throws Exception {
        BigDecimal sum = single(engine, "SELECT SUM(\"Amount\") AS s FROM resultSet",
                "[{\"Amount\":5,\"Region\":\"n\"},{\"Amount\":7,\"Region\":\"n\"}]", "s");
        assertEquals(0, BigDecimal.valueOf(12).compareTo(sum));
    }

    /** The data's fault, not the server's: must throw (the API layer maps it to a 400). */
    @ParameterizedTest
    @MethodSource("engines")
    void aggregatingAStringThrows(AggregationEngine engine) {
        assertThrows(RuntimeException.class,
                () -> engine.query("SELECT SUM(zip) AS s FROM resultSet", ROWS));
    }
}
