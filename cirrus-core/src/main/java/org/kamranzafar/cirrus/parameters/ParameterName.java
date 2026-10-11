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
package org.kamranzafar.cirrus.parameters;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Portable parameter names: segments of letters, digits and single inner dashes, joined by {@code /},
 * such as {@code orders/db/password}. This is the intersection every backend can store: providers
 * translate the separator (Key Vault, for one, allows only letters, digits and dashes).
 */
public final class ParameterName {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9]+(-[A-Za-z0-9]+)*");

    private ParameterName() {
    }

    /** Validates a name (or prefix) and returns it. */
    public static String validate(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Parameter name must not be empty");
        }
        for (String segment : name.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid parameter name '" + name + "': use segments of letters, "
                        + "digits and single dashes separated by '/', such as orders/db/password");
            }
        }
        return name;
    }

    /** Joins a store prefix and a relative name. */
    public static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }
}
