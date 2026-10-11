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
package org.kamranzafar.cirrus.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small JSON reader and writer, so core and the Vault provider need no JSON library (which could
 * clash with the cloud SDKs' own). Objects become {@code Map<String, Object>}, arrays
 * {@code List<Object>}, numbers {@code Long} or {@code Double}.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        json.whitespace();
        Object value = json.value();
        json.whitespace();
        if (json.pos != text.length()) {
            throw json.error("Unexpected trailing content");
        }
        return value;
    }

    /** Walks a path of object keys; returns null if any step is missing or not an object. */
    @SuppressWarnings("unchecked")
    public static Object at(Object root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Parses {@code text} if it is a JSON object, or returns empty. */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> parseObject(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{")) {
            return Optional.empty();
        }
        try {
            return parse(trimmed) instanceof Map<?, ?> map ? Optional.of((Map<String, Object>) map) : Optional.empty();
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    /**
     * Flattens an object into dotted keys, like Spring property names: {@code {"db":{"host":"h"}}}
     * becomes {@code db.host=h}, arrays become {@code list[0]}. Nulls are skipped.
     */
    public static Map<String, String> flatten(Map<String, Object> object) {
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", object, flat);
        return flat;
    }

    private static void flatten(String path, Object value, Map<String, String> out) {
        switch (value) {
            case null -> { }
            case Map<?, ?> map -> map.forEach((k, v) -> flatten(path.isEmpty() ? String.valueOf(k) : path + "." + k, v, out));
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    flatten(path + "[" + i + "]", list.get(i), out);
                }
            }
            case Double d when d == Math.rint(d) && !Double.isInfinite(d) -> out.put(path, String.valueOf(d.longValue()));
            default -> out.put(path, String.valueOf(value));
        }
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Number n -> out.append(n);
            case Boolean b -> out.append(b);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private Object value() {
        if (pos >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        whitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            whitespace();
            String key = string();
            whitespace();
            expect(':');
            whitespace();
            map.put(key, value());
            whitespace();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        whitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            whitespace();
            list.add(value());
            whitespace();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("Unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("Bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String n = text.substring(start, pos);
        if (n.isEmpty()) {
            throw error("Unexpected character");
        }
        return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("Expected " + word);
        }
        pos += word.length();
        return value;
    }

    private void whitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + pos + " of JSON");
    }
}
