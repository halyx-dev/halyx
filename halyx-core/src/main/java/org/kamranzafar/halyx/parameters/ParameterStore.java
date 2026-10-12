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
package org.kamranzafar.halyx.parameters;

import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.NotFoundException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Portable parameters and secrets: SSM Parameter Store / Secrets Manager, App Configuration /
 * Key Vault, Parameter Manager / Secret Manager, and HashiCorp Vault. Names are relative to the
 * store's prefix and follow {@link ParameterName}. Thread-safe.
 */
public interface ParameterStore {

    String provider();

    ParameterKind kind();

    /** The current value, or empty. */
    Optional<Parameter> get(String name);

    /** A specific version. Requires {@link ParameterCapability#VERSIONS}. */
    Optional<Parameter> get(String name, String version);

    /** The current value, failing if absent. */
    default String require(String name) {
        return get(name).map(Parameter::value)
                .orElseThrow(() -> new NotFoundException("No " + kind().name().toLowerCase() + " named '" + name + "'"));
    }

    /**
     * One field of a JSON secret, failing if the secret or field is absent:
     * {@code require("orders/db", "password")} for an RDS-managed secret.
     */
    default String require(String name, String field) {
        return get(name).flatMap(p -> p.field(field))
                .orElseThrow(() -> new NotFoundException("No field '" + field + "' in " + kind().name().toLowerCase()
                        + " '" + name + "'"));
    }

    /** The current value converted, such as {@code get("pool/size", Integer::valueOf)}. */
    default <T> Optional<T> get(String name, Function<String, T> converter) {
        return get(name).map(Parameter::value).map(converter);
    }

    /** Every value at or below {@code prefix} (empty for all), keyed by name. */
    Map<String, Parameter> getAll(String prefix);

    /** Creates or replaces a value and returns the stored version. */
    Parameter put(String name, String value);

    /** Deletes a value with all its versions. Idempotent. */
    void delete(String name);

    /** Drops cached reads, so the next read goes to the backend. */
    void refresh();

    /**
     * Polls {@code prefix} (empty for all) every {@code interval} and reports added, updated and removed
     * values to {@code listener}, so applications can follow rotations without restarting. The first
     * poll establishes the baseline. Polling failures are logged and retried at the next interval.
     * Close the returned handle to stop.
     */
    AutoCloseable watch(String prefix, Duration interval, Consumer<ParameterChanges> listener);

    /** Read and cache counters of this store instance. */
    ParameterStoreStatistics statistics();

    Capabilities capabilities();

    <T> T unwrap(Class<T> nativeType);
}
