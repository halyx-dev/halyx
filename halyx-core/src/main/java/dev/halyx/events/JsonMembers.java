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
package dev.halyx.events;

import dev.halyx.json.Json;

import java.util.Optional;

/**
 * The raw text of a top-level member of a JSON object, so data that is itself JSON keeps the formatting and number
 * precision it arrived with, rather than going through a parse and write. Expects valid JSON.
 */
final class JsonMembers {

    private JsonMembers() {
    }

    static Optional<String> raw(String json, String name) {
        int i = skipSpace(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') {
            return Optional.empty();
        }
        i++;
        while (true) {
            i = skipSpace(json, i);
            if (i >= json.length() || json.charAt(i) == '}') {
                return Optional.empty();
            }
            int keyEnd = endOfValue(json, i);
            String key = (String) Json.parse(json.substring(i, keyEnd));
            i = skipSpace(json, keyEnd);
            i = skipSpace(json, i + 1); // the colon
            int valueEnd = endOfValue(json, i);
            if (key.equals(name)) {
                return Optional.of(json.substring(i, valueEnd));
            }
            i = skipSpace(json, valueEnd);
            if (i < json.length() && json.charAt(i) == ',') {
                i++;
            }
        }
    }

    /** The index just after the JSON value starting at {@code start}. */
    private static int endOfValue(String json, int start) {
        char c = json.charAt(start);
        if (c == '"') {
            int i = start + 1;
            while (json.charAt(i) != '"') {
                i += json.charAt(i) == '\\' ? 2 : 1;
            }
            return i + 1;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            int i = start;
            while (true) {
                char d = json.charAt(i);
                if (d == '"') {
                    i = endOfValue(json, i);
                    continue;
                }
                if (d == '{' || d == '[') {
                    depth++;
                } else if (d == '}' || d == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
        }
        int i = start;
        while (i < json.length() && ",}] \t\r\n".indexOf(json.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    private static int skipSpace(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }
}
