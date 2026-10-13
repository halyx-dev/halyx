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
package dev.halyx.memory;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.spi.AbstractParameterDriver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/** In-memory parameters with numbered versions, like SSM Parameter Store. */
final class InMemoryParameterDriver extends AbstractParameterDriver {

    private final ConcurrentSkipListMap<String, List<Parameter>> values;

    InMemoryParameterDriver(ConcurrentSkipListMap<String, List<Parameter>> values, boolean nativeNames) {
        super(InMemoryProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.values = values;
    }

    @Override
    public Optional<Parameter> get(String name) {
        List<Parameter> versions = values.get(name);
        return versions == null ? Optional.empty() : Optional.of(versions.getLast());
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        List<Parameter> versions = values.get(name);
        return versions == null ? Optional.empty()
                : versions.stream().filter(p -> p.version().equals(version)).findFirst();
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        Map<String, Parameter> found = new LinkedHashMap<>();
        values.forEach((name, versions) -> {
            if (matches(name, prefix)) {
                found.put(name, versions.getLast());
            }
        });
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        List<Parameter> versions = values.compute(name, (k, existing) -> {
            List<Parameter> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(new Parameter(name, value, String.valueOf(next.size() + 1), Instant.now()));
            return List.copyOf(next);
        });
        return versions.getLast();
    }

    @Override
    public void delete(String name) {
        values.remove(name);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of();
    }
}
