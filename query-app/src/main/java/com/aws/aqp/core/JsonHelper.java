// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import javax.json.*;
import java.math.BigDecimal;
import java.util.*;

public class JsonHelper {

    public static JsonArray toJson(List<AttributeValue> attributeValues) {
        if (attributeValues == null) {
            return null;
        }
        JsonArrayBuilder valueBuilder = Json.createArrayBuilder();
        for (AttributeValue a : attributeValues) {
            add(toJson(a), valueBuilder);
        }
        return valueBuilder.build();
    }

    public static JsonObject toJson(Map<String, AttributeValue> attributeValues) {
        if (attributeValues == null) {
            return null;
        }
        JsonObjectBuilder valueBuilder = Json.createObjectBuilder();
        for (Map.Entry<String, AttributeValue> a : attributeValues.entrySet()) {
            add(a.getKey(), toJson(a.getValue()), valueBuilder);
        }
        return valueBuilder.build();
    }

    public static void add(String key, Object value, JsonObjectBuilder object) {
        if (value instanceof JsonValue) {
            object.add(key, (JsonValue) value);

        } else if (value instanceof String) {
            object.add(key, (String) value);
        } else if (value instanceof BigDecimal) {
            object.add(key, (BigDecimal) value);
        } else if (value instanceof Boolean) {
            object.add(key, (Boolean) value);
        } else if (value == null || value.equals(JsonValue.NULL)) {
            object.addNull(key);
        }

    }

    public static void add(Object value, JsonArrayBuilder array) {
        if (value instanceof JsonValue) {
            array.add((JsonValue) value);
        } else if (value instanceof String) {
            array.add((String) value);
        } else if (value instanceof BigDecimal) {
            array.add((BigDecimal) value);
        } else if (value instanceof Boolean) {
            array.add((Boolean) value);
        } else if (value.equals(JsonValue.NULL)) {
            array.addNull();
        }

    }

    public static Object toJson(AttributeValue attributeValue) {

        if (attributeValue == null) {
            return null;
        }
        if (attributeValue.s() != null) {
            return attributeValue.s();
        }
        if (attributeValue.n() != null) {
            return new BigDecimal(attributeValue.n());
        }
        if (attributeValue.bool() != null) {
            return attributeValue.bool();
        }

        if (attributeValue.b() != null) {
            return new byte[0];
        }

        if (attributeValue.nul() != null && attributeValue.nul()) {
            return JsonValue.NULL;
        }

        if (!attributeValue.m().isEmpty()) {
            return toJson(attributeValue.m());
        }
        if (!attributeValue.l().isEmpty()) {
            return toJson(attributeValue.l());
        }

        if (!attributeValue.ss().isEmpty()) {
            return attributeValue.ss();
        }

        if (!attributeValue.ns().isEmpty()) {
            return attributeValue.ns();
        }

        if (!attributeValue.bs().isEmpty()) {
            return attributeValue.bs();
        }
        return null;
    }

    public static Map<String, AttributeValue> toAttribute(JsonObject jsonObject) {
        Map<String, AttributeValue> attribute = new HashMap<>();
        jsonObject.entrySet().forEach(e -> {
            attribute.put(e.getKey(), toAttribute(e.getValue()));
        });
        return attribute;
    }

    public static List<AttributeValue> toAttribute(JsonArray jsonArray) {
        List<AttributeValue> attributes = new LinkedList<>();
        jsonArray.forEach(e -> {
            attributes.add(toAttribute(e));
        });
        return attributes;
    }

    public static AttributeValue toAttribute(JsonValue jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        switch (jsonValue.getValueType()) {
            case STRING:
                return AttributeValue.builder().s(((JsonString) jsonValue).getString()).build();
            case OBJECT:
                return AttributeValue.builder().m(toAttribute((JsonObject) jsonValue)).build();
            case ARRAY:
                return AttributeValue.builder().l(toAttribute((JsonArray) jsonValue)).build();
            case NUMBER:
                return AttributeValue.builder().n(((JsonNumber) jsonValue).toString()).build();
            case TRUE:
                return AttributeValue.builder().bool(true).build();
            case FALSE:
                return AttributeValue.builder().bool(false).build();
            case NULL:
                return AttributeValue.builder().nul(true).build();
        }

        return AttributeValue.builder().s("Empty").build();
    }


}
