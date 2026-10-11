/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.cirrus.azure.cosmos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kamranzafar.cirrus.nosql.Document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts between portable values and Cosmos DB JSON. Cosmos keeps JSON numbers as doubles, so values a
 * double can't hold exactly travel in one-field wrapper objects: longs beyond 2^53 as {@value #LONG},
 * decimals as {@value #DECIMAL}, and binary as base64 in {@value #BINARY}.
 */
final class CosmosValues {

    static final String LONG = "cirrus_long";
    static final String DECIMAL = "cirrus_decimal";
    static final String BINARY = "cirrus_binary";
    private static final long SAFE = 1L << 53;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CosmosValues() {
    }

    static JsonNode toJson(Object value) {
        return switch (value) {
            case null -> NODES.nullNode();
            case String s -> NODES.textNode(s);
            case Boolean b -> NODES.booleanNode(b);
            case Long l when l > -SAFE && l < SAFE -> NODES.numberNode(l);
            case Long l -> wrapper(LONG, l.toString());
            case Double d -> NODES.numberNode(d);
            case BigDecimal bd -> wrapper(DECIMAL, bd.toPlainString());
            case byte[] bytes -> wrapper(BINARY, Base64.getEncoder().encodeToString(bytes));
            case Document d -> toJson(d, Set.of());
            case List<?> list -> {
                ArrayNode array = NODES.arrayNode();
                list.forEach(v -> array.add(toJson(v)));
                yield array;
            }
            default -> throw new IllegalArgumentException("Unsupported value " + value.getClass());
        };
    }

    static ObjectNode toJson(Document document, Set<String> skip) {
        ObjectNode node = NODES.objectNode();
        for (String field : document.fieldNames()) {
            if (!skip.contains(field)) {
                node.set(field, toJson(document.get(field)));
            }
        }
        return node;
    }

    private static ObjectNode wrapper(String tag, String text) {
        ObjectNode node = NODES.objectNode();
        node.put(tag, text);
        return node;
    }

    static Object fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            double d = node.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < SAFE && !node.toString().contains(".") ? (Object) (long) d : d;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(v -> out.add(fromJson(v)));
            return out;
        }
        if (node.isObject() && node.size() == 1) {
            if (node.has(LONG)) {
                return Long.parseLong(node.get(LONG).textValue());
            }
            if (node.has(DECIMAL)) {
                return new BigDecimal(node.get(DECIMAL).textValue());
            }
            if (node.has(BINARY)) {
                return Base64.getDecoder().decode(node.get(BINARY).textValue());
            }
        }
        return fromJson((ObjectNode) node, Set.of());
    }

    static Document fromJson(ObjectNode node, Set<String> skip) {
        Document.Builder b = Document.builder();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!skip.contains(field.getKey())) {
                b.put(field.getKey(), fromJson(field.getValue()));
            }
        }
        return b.build();
    }
}
