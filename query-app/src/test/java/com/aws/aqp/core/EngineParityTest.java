// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stronger than the contract test: for a corpus of aggregation queries over one dataset, the
 * two engines must return the same rows — same column names, same values (numbers compared
 * numerically, so 6.5 equals 6.500). Queries whose behaviour is a documented delta between the
 * engines (projection of missing attributes, {@code /} division, unaliased aggregates) are
 * deliberately absent; they are covered by the per-engine tests.
 */
class EngineParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final String DOCUMENT = "{\"resultSet\":["
            + "{\"zip\":\"Z1\",\"amount\":10,\"pk\":\"a\",\"m\":{\"v\":1},\"big\":12345678901234567890},"
            + "{\"zip\":\"Z1\",\"amount\":10,\"pk\":\"b\",\"m\":{\"v\":2},\"big\":1},"
            + "{\"zip\":\"Z2\",\"amount\":5,\"pk\":\"c\",\"frac\":0.12345678901234567890123},"
            + "{\"zip\":\"Z3\",\"amount\":1,\"pk\":\"d\",\"frac\":0.1}]}";

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(pk) AS n FROM resultSet",
            "SELECT COUNT(*) AS n FROM resultSet",
            "SELECT COUNT(DISTINCT zip) AS n FROM resultSet",
            "SELECT SUM(amount) AS s, MIN(amount) AS lo, MAX(amount) AS hi FROM resultSet",
            "SELECT AVG(amount) AS a FROM resultSet",
            "SELECT zip, SUM(amount) AS t FROM resultSet GROUP BY zip",
            "SELECT SUM(amount) AS t FROM resultSet GROUP BY zip",
            "SELECT zip, COUNT(pk) AS c FROM resultSet GROUP BY zip HAVING SUM(amount) > 2 ORDER BY zip",
            "SELECT zip, SUM(amount) AS t FROM resultSet GROUP BY zip ORDER BY t DESC",
            "SELECT SUM(m.v) AS nested FROM resultSet",
            "SELECT SUM(big) AS s FROM resultSet",
            "SELECT MAX(frac) AS m FROM resultSet",
            "SELECT COUNT(frac) AS present, COUNT(*) AS total FROM resultSet",
            "SELECT zip FROM resultSet GROUP BY zip HAVING COUNT(*) > 5",
    })
    void bothEnginesReturnTheSameRows(String sql) throws Exception {
        boolean ordered = sql.contains("ORDER BY");
        List<TreeMap<String, Object>> partiql = normalize(
                MAPPER.readTree(new PartiQLEngine().query(sql, DOCUMENT)), ordered);
        List<TreeMap<String, Object>> duckdb = normalize(
                MAPPER.readTree(new DuckDbEngine(64L * 1024 * 1024).query(sql, DOCUMENT)), ordered);
        assertEquals(partiql, duckdb, () -> "engines disagree on: " + sql);
    }

    /** Rows as comparable maps: numbers canonicalised, row order ignored unless ORDER BY. */
    private static List<TreeMap<String, Object>> normalize(JsonNode rows, boolean ordered) {
        List<TreeMap<String, Object>> normalized = new ArrayList<>();
        for (JsonNode row : rows) {
            TreeMap<String, Object> map = new TreeMap<>();
            row.fields().forEachRemaining(e -> map.put(e.getKey(), normalizeValue(e.getValue())));
            normalized.add(map);
        }
        if (!ordered) {
            normalized.sort(Comparator.comparing(Object::toString));
        }
        return normalized;
    }

    private static Object normalizeValue(JsonNode value) {
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue().stripTrailingZeros();
            return decimal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : decimal;
        }
        if (value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.asText();
    }
}
