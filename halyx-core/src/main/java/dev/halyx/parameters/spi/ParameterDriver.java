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
package dev.halyx.parameters.spi;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.parameters.Parameter;

import java.util.Map;
import java.util.Optional;

/**
 * Provider SPI for parameter stores. Names are full portable names (store prefix applied, validated);
 * drivers translate them to the backend's naming rules and back. Returned parameters carry full names.
 */
public interface ParameterDriver {

    Optional<Parameter> get(String name);

    Optional<Parameter> get(String name, String version);

    /**
     * Every parameter whose name equals {@code prefix} or starts with {@code prefix + "/"} (all for "");
     * with native names, every parameter whose name starts with {@code prefix}.
     */
    Map<String, Parameter> list(String prefix);

    Parameter put(String name, String value);

    void delete(String name);

    Capabilities capabilities();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);
}
