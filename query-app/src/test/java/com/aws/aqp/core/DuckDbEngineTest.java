// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.core.errors.InvalidQueryException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DuckDB-specific behaviour; everything both engines share is in the engine contract test. */
class DuckDbEngineTest {

    private static final String ROWS = "{\"resultSet\":["
            + "{\"zip\":\"Z1\",\"amount\":10,\"pk\":\"a\"},{\"zip\":\"Z1\",\"amount\":10,\"pk\":\"b\"},"
            + "{\"zip\":\"Z2\",\"amount\":5,\"pk\":\"c\"},{\"zip\":\"Z3\",\"amount\":1,\"pk\":\"d\"}]}";

    private final DuckDbEngine engine = new DuckDbEngine(64L * 1024 * 1024);
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonNode rows(String sql, String document) throws Exception {
        return mapper.readTree(engine.query(sql, document));
    }

    private BigDecimal single(String sql, String rowsJson, String column) throws Exception {
        return rows(sql, "{\"resultSet\":" + rowsJson + "}").get(0).get(column).decimalValue();
    }












    /** Documented delta: SQL projects the absent attribute as null; PartiQL omits the field. */
    @Test
    void projectsAMissingAttributeAsNull() throws Exception {
        JsonNode out = rows("SELECT x FROM resultSet ORDER BY x",
                "{\"resultSet\":[{\"x\":1},{\"y\":2}]}");
        assertTrue(out.get(1).hasNonNull("x") != out.get(1).has("x") || out.get(1).get("x").isNull(),
                out::toString);
    }


    /** DuckDB identifiers are case-insensitive; colliding spellings must fail clearly. */
    @Test
    void rejectsAttributesDifferingOnlyInCase() {
        InvalidQueryException e = assertThrows(InvalidQueryException.class, () ->
                engine.query("SELECT COUNT(*) AS n FROM resultSet",
                        "{\"resultSet\":[{\"amount\":1},{\"Amount\":2}]}"));
        assertTrue(e.getMessage().contains("case"), e::getMessage);
    }

    /** Mixed types in one attribute: COUNT works over the JSON-text fallback, arithmetic is a 400. */
    @Test
    void mixedTypeAttributesCountButDoNotSum() throws Exception {
        String rows = "{\"resultSet\":[{\"x\":1},{\"x\":\"two\"},{\"x\":3}]}";
        assertEquals(3, rows("SELECT COUNT(x) AS c FROM resultSet", rows).get(0).get("c").asInt());
        InvalidQueryException e = assertThrows(InvalidQueryException.class,
                () -> engine.query("SELECT SUM(x) AS s FROM resultSet", rows));
        assertTrue(e.getMessage().contains("could not evaluate"), e::getMessage);
    }

    // ------------------------------------------------------------------------ errors & deltas

    @Test
    void reportsBadQueriesWithTheRealBinderError() {
        InvalidQueryException e = assertThrows(InvalidQueryException.class,
                () -> engine.query("SELECT SUM(zip) AS s FROM resultSet", ROWS));
        assertTrue(e.getMessage().contains("sum") || e.getMessage().contains("SUM"), e::getMessage);
    }

    @Test
    void reportsUnknownColumnsAsInvalidQueries() {
        assertThrows(InvalidQueryException.class,
                () -> engine.query("SELECT SUM(nope) AS s FROM resultSet", ROWS));
    }

    /** Documented delta: SQL float division, where PartiQL keeps integer semantics (3). */
    @Test
    void divisionIsFloatDivision() throws Exception {
        assertEquals(0, new BigDecimal("3.5").compareTo(
                single("SELECT x / 2 AS h FROM resultSet", "[{\"x\":7}]", "h")));
    }

}
