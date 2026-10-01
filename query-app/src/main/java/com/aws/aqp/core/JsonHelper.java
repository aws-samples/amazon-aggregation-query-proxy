// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;

/**
 * Converts DynamoDB items to JSON for the aggregation step.
 * <p>
 * Every attribute type is represented; nothing is silently dropped. The previous javax.json
 * version returned sets as Java Lists and binary as an empty byte[], neither of which its
 * builder handled, so those attributes vanished from the row. It also tested collections with
 * {@code l().isEmpty()}, so an empty list or map became null and changed COUNT results.
 */
public final class JsonHelper {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonHelper() {
    }

    public static ObjectNode toJson(Map<String, AttributeValue> item) {
        ObjectNode object = NODES.objectNode();
        for (Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
            object.set(attribute.getKey(), toJson(attribute.getValue()));
        }
        return object;
    }

    public static JsonNode toJson(AttributeValue value) {
        if (value == null) {
            return NODES.nullNode();
        }
        switch (value.type()) {
            case S:
                return NODES.textNode(value.s());
            case N:
                return NODES.numberNode(new BigDecimal(value.n()));
            case BOOL:
                return NODES.booleanNode(value.bool());
            case NUL:
                return NODES.nullNode();
            case B:
                return NODES.textNode(base64(value.b()));
            case M:
                return toJson(value.m());
            case L: {
                ArrayNode array = NODES.arrayNode();
                value.l().forEach(element -> array.add(toJson(element)));
                return array;
            }
            case SS: {
                ArrayNode array = NODES.arrayNode();
                value.ss().forEach(array::add);
                return array;
            }
            case NS: {
                ArrayNode array = NODES.arrayNode();
                value.ns().forEach(n -> array.add(new BigDecimal(n)));
                return array;
            }
            case BS: {
                ArrayNode array = NODES.arrayNode();
                value.bs().forEach(b -> array.add(base64(b)));
                return array;
            }
            default:
                // UNKNOWN_TO_SDK_VERSION: a type newer than this SDK. Failing is better than
                // aggregating over a row with the attribute quietly missing.
                throw new IllegalStateException("Unsupported DynamoDB attribute type: " + value.type());
        }
    }

    private static String base64(SdkBytes bytes) {
        return Base64.getEncoder().encodeToString(bytes.asByteArrayUnsafe());
    }
}
