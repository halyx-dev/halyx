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
package org.kamranzafar.cirrus.spring.autoconfigure.cache;

import java.util.List;

/** Which classes cached values may deserialize to: {@code java.*} plus configured package prefixes. */
final class TrustedClasses {

    private final List<String> packages;

    TrustedClasses(List<String> packages) {
        this.packages = packages.stream().map(p -> p.endsWith(".") ? p : p + ".").toList();
    }

    boolean isRestricted() {
        return !packages.isEmpty();
    }

    boolean allows(String className) {
        if (className.startsWith("java.") || className.equals("org.springframework.cache.support.NullValue")) {
            return true;
        }
        return packages.stream().anyMatch(className::startsWith);
    }

    Class<?> load(String className) {
        if (!allows(className)) {
            throw new IllegalArgumentException("Cached value of type " + className
                    + " is not in cirrus.cache-manager.trusted-packages " + packages);
        }
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cached value type not found: " + className, e);
        }
    }
}
