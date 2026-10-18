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
package dev.halyx.parameters;

import dev.halyx.error.NotFoundException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link ParameterStore}, from {@link ParameterStore#async()}: reads and writes as
 * {@code CompletableFuture}s, with no thread waiting for the backend where the driver is non-blocking. The read
 * cache, statistics, interceptors and error translation are those of the blocking calls, and a cached read
 * completes at once. Watching and refreshing are on {@link #blocking()}.
 */
public interface AsyncParameterStore {

    String provider();

    ParameterKind kind();

    /** The current value, or empty. */
    CompletableFuture<Optional<Parameter>> get(String name);

    /** A specific version. Requires {@link ParameterCapability#VERSIONS}. */
    CompletableFuture<Optional<Parameter>> get(String name, String version);

    /** The current value, failing with a {@link NotFoundException} if absent. */
    default CompletableFuture<String> require(String name) {
        return get(name).thenApply(found -> found.map(Parameter::value).orElseThrow(() -> new NotFoundException(
                "No " + kind().name().toLowerCase() + " named '" + name + "'")));
    }

    /** Every value at or below {@code prefix} (empty for all), keyed by name. */
    CompletableFuture<Map<String, Parameter>> getAll(String prefix);

    /** Creates or replaces a value and returns the stored version. */
    CompletableFuture<Parameter> put(String name, String value);

    /** Deletes a value with all its versions. Idempotent. */
    CompletableFuture<Void> delete(String name);

    /** Completes when the backend answers an authenticated read, past the read cache. */
    CompletableFuture<Void> ping();

    /** The blocking store this is a view of. */
    ParameterStore blocking();

    /** A view that runs each of {@code store}'s blocking calls on {@code executor}: for stores without one of their own. */
    static AsyncParameterStore onExecutor(ParameterStore store, Executor executor) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(executor, "executor");
        return new AsyncParameterStore() {
            @Override
            public String provider() {
                return store.provider();
            }

            @Override
            public ParameterKind kind() {
                return store.kind();
            }

            @Override
            public CompletableFuture<Optional<Parameter>> get(String name) {
                return CompletableFuture.supplyAsync(() -> store.get(name), executor);
            }

            @Override
            public CompletableFuture<Optional<Parameter>> get(String name, String version) {
                return CompletableFuture.supplyAsync(() -> store.get(name, version), executor);
            }

            @Override
            public CompletableFuture<Map<String, Parameter>> getAll(String prefix) {
                return CompletableFuture.supplyAsync(() -> store.getAll(prefix), executor);
            }

            @Override
            public CompletableFuture<Parameter> put(String name, String value) {
                return CompletableFuture.supplyAsync(() -> store.put(name, value), executor);
            }

            @Override
            public CompletableFuture<Void> delete(String name) {
                return CompletableFuture.runAsync(() -> store.delete(name), executor);
            }

            @Override
            public CompletableFuture<Void> ping() {
                return CompletableFuture.runAsync(store::ping, executor);
            }

            @Override
            public ParameterStore blocking() {
                return store;
            }
        };
    }
}
