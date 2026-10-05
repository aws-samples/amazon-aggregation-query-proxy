// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonHelperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode convert(Map<String, AttributeValue> item) throws Exception {
        return mapper.readTree(JsonHelper.toJson(item).toString());
    }

    @Test
    void convertsScalars() throws Exception {
        JsonNode json = convert(Map.of(
                "s", AttributeValue.builder().s("text \"quoted\"").build(),
                "n", AttributeValue.builder().n("12.50").build(),
                "b", AttributeValue.builder().bool(true).build(),
                "z", AttributeValue.builder().nul(true).build()));

        assertEquals("text \"quoted\"", json.get("s").asText());
        assertEquals(0, json.get("n").decimalValue().compareTo(new java.math.BigDecimal("12.50")));
        assertTrue(json.get("b").asBoolean());
        assertTrue(json.get("z").isNull());
    }

    @Test
    void convertsNestedMapsAndLists() throws Exception {
        JsonNode json = convert(Map.of(
                "m", AttributeValue.builder().m(Map.of(
                        "city", AttributeValue.builder().s("Seattle").build())).build(),
                "l", AttributeValue.builder().l(
                        AttributeValue.builder().n("1").build(),
                        AttributeValue.builder().s("two").build()).build()));

        assertEquals("Seattle", json.get("m").get("city").asText());
        assertEquals(1, json.get("l").get(0).asInt());
        assertEquals("two", json.get("l").get(1).asText());
    }

    /** Sets were returned as Java Lists that the builder had no branch for, so they vanished. */
    @Test
    void keepsStringAndNumberSets() throws Exception {
        JsonNode json = convert(Map.of(
                "pk", AttributeValue.builder().s("a").build(),
                "tags", AttributeValue.builder().ss("red", "blue").build(),
                "scores", AttributeValue.builder().ns("1", "2.5").build()));

        assertTrue(json.has("tags"), () -> "string set was dropped: " + json);
        assertEquals(2, json.get("tags").size());
        assertTrue(json.has("scores"), () -> "number set was dropped: " + json);
        assertTrue(json.get("scores").get(0).isNumber(), () -> "number set members must be numbers: " + json);
    }

    /** Binary was converted to an empty byte[] that the builder also silently skipped. */
    @Test
    void keepsBinaryAsBase64() throws Exception {
        SdkBytes bytes = SdkBytes.fromString("hello", StandardCharsets.UTF_8);
        JsonNode json = convert(Map.of(
                "bin", AttributeValue.builder().b(bytes).build(),
                "bins", AttributeValue.builder().bs(List.of(bytes)).build()));

        assertTrue(json.has("bin"), () -> "binary was dropped: " + json);
        assertEquals("aGVsbG8=", json.get("bin").asText());
        assertEquals("aGVsbG8=", json.get("bins").get(0).asText());
    }

    /** An empty list or map is a value, not an absent attribute. */
    @Test
    void keepsEmptyCollections() throws Exception {
        JsonNode json = convert(Map.of(
                "emptyList", AttributeValue.builder().l(List.of()).build(),
                "emptyMap", AttributeValue.builder().m(Map.of()).build()));

        assertTrue(json.get("emptyList").isArray(), json::toString);
        assertTrue(json.get("emptyMap").isObject(), json::toString);
    }
}
