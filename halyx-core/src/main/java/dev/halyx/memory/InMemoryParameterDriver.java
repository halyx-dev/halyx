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
import dev.halyx.keys.spi.ServerSideKey;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.spi.AbstractParameterDriver;
import dev.halyx.parameters.spi.ParameterDriver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory parameters with numbered versions, like SSM Parameter Store. A server-side key is recorded when a parameter
 * is created, as most services apply it, and read back through {@link InMemoryParameters}.
 */
final class InMemoryParameterDriver extends AbstractParameterDriver {

    /** A parameter's versions, oldest first, and the native id of the key it was created with, or null. */
    record Entry(List<Parameter> versions, String serverSideKey) {
    }

    private final ConcurrentSkipListMap<String, Entry> values;
    /** The key new parameters are created with, for a driver from {@link #withServerSideKey}, or null. */
    private final ServerSideKey writeKey;

    InMemoryParameterDriver(ConcurrentSkipListMap<String, Entry> values, boolean nativeNames) {
        this(values, nativeNames, null);
    }

    private InMemoryParameterDriver(ConcurrentSkipListMap<String, Entry> values, boolean nativeNames,
                                    ServerSideKey writeKey) {
        super(InMemoryProvider.ID, Capabilities.of(ParameterCapability.VERSIONS, ParameterCapability.SERVER_SIDE_KMS),
                nativeNames);
        this.values = values;
        this.writeKey = writeKey;
    }

    @Override
    public ParameterDriver withServerSideKey(ServerSideKey key) {
        return new InMemoryParameterDriver(values, nativeNames, key);
    }

    @Override
    public Optional<Parameter> get(String name) {
        Entry entry = values.get(name);
        return entry == null ? Optional.empty() : Optional.of(entry.versions().getLast());
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        Entry entry = values.get(name);
        return entry == null ? Optional.empty()
                : entry.versions().stream().filter(p -> p.version().equals(version)).findFirst();
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        Map<String, Parameter> found = new LinkedHashMap<>();
        values.forEach((name, entry) -> {
            if (matches(name, prefix)) {
                found.put(name, entry.versions().getLast());
            }
        });
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        Entry stored = values.compute(name, (k, existing) -> {
            List<Parameter> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing.versions());
            next.add(new Parameter(name, value, String.valueOf(next.size() + 1), Instant.now()));
            String key = existing != null ? existing.serverSideKey() : writeKey != null ? writeKey.nativeId() : null;
            return new Entry(List.copyOf(next), key);
        });
        return stored.versions().getLast();
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
        return List.of(new InMemoryParameters(values));
    }
}
