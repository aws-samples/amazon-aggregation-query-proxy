// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PartiQL 0.14 converts Ion integers to Java longs while binding its input, so an integer
 * outside the 64-bit range silently wrapped: 12345678901234567890 became -6101065172474983726,
 * even in a plain projection. DynamoDB numbers carry up to 38 digits. (PartiQL 0.7 did not have
 * this problem; it arrived with the upgrade.)
 */
class IonEngineTest {

    private static final String HUGE = "12345678901234567890";
    private static final String HUGER = "99999999999999999999999999999999999999"; // 38 digits

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private BigDecimal single(String sql, String rows, String column) throws Exception {
        JsonNode result = mapper.readTree(new IonEngine().query(sql, "{\"resultSet\":" + rows + "}"));
        return result.get(0).get(column).decimalValue();
    }

    @Test
    void projectsIntegersBeyond64BitsExactly() throws Exception {
        assertEquals(0, new BigDecimal(HUGE).compareTo(
                single("SELECT x FROM resultSet", "[{\"x\":" + HUGE + "}]", "x")));
    }

    @Test
    void sumsIntegersBeyond64BitsExactly() throws Exception {
        BigDecimal sum = single("SELECT SUM(x) AS s FROM resultSet",
                "[{\"x\":" + HUGER + "},{\"x\":1}]", "s");
        assertEquals(0, new BigDecimal(HUGER).add(BigDecimal.ONE).compareTo(sum), sum::toPlainString);
    }

    @Test
    void convertsNestedIntegersToo() throws Exception {
        BigDecimal sum = single("SELECT SUM(r.m.v) AS s FROM resultSet r",
                "[{\"m\":{\"v\":" + HUGE + "}}]", "s");
        assertEquals(0, new BigDecimal(HUGE).compareTo(sum), sum::toPlainString);
    }

    /** In-range integers keep integer semantics: 7 / 2 is still integer division. */
    @Test
    void leavesInRangeIntegersAsIntegers() throws Exception {
        assertEquals(3, single("SELECT x / 2 AS h FROM resultSet", "[{\"x\":7}]", "h").intValueExact());
    }
}
