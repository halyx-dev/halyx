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
package dev.halyx.reactor;

import dev.halyx.capability.Capabilities;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterChanges;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreStatistics;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link ParameterStore} as {@code Mono}s: each call runs when subscribed, once per subscription, on the
 * application's scheduler, through the store's own cache. A missing parameter is an empty {@code Mono}.
 */
public final class ReactiveParameterStore {

    private final ParameterStore store;
    private final Calls calls;

    ReactiveParameterStore(ParameterStore store, Calls calls) {
        this.store = Objects.requireNonNull(store, "store");
        this.calls = calls;
    }

    public String provider() {
        return store.provider();
    }

    public ParameterKind kind() {
        return store.kind();
    }

    public Capabilities capabilities() {
        return store.capabilities();
    }

    public ParameterStoreStatistics statistics() {
        return store.statistics();
    }

    /** The blocking store this wraps. */
    public ParameterStore blocking() {
        return store;
    }

    public Mono<Parameter> get(String name) {
        return calls.optional(() -> store.get(name));
    }

    public Mono<Parameter> get(String name, String version) {
        return calls.optional(() -> store.get(name, version));
    }

    /** The value; fails with {@code NotFoundException} if there is none, as {@link ParameterStore#require} does. */
    public Mono<String> require(String name) {
        return calls.mono(() -> store.require(name));
    }

    /** A field of a JSON value; fails with {@code NotFoundException} if the parameter or field is missing. */
    public Mono<String> require(String name, String field) {
        return calls.mono(() -> store.require(name, field));
    }

    public Mono<Map<String, Parameter>> getAll(String prefix) {
        return calls.mono(() -> store.getAll(prefix));
    }

    public Mono<Parameter> put(String name, String value) {
        return calls.mono(() -> store.put(name, value));
    }

    public Mono<Void> delete(String name) {
        return calls.run(() -> store.delete(name));
    }

    /** Drops the store's cached values. */
    public Mono<Void> refresh() {
        return calls.run(store::refresh);
    }

    /**
     * Changes under {@code prefix}, checked every {@code interval} by the store's watch, from when the subscriber
     * arrives; the watch stops when it goes away.
     */
    public Flux<ParameterChanges> watch(String prefix, Duration interval) {
        return Flux.create(sink -> {
            AutoCloseable watch;
            try {
                watch = store.watch(prefix, interval, sink::next);
            } catch (RuntimeException e) {
                sink.error(e);
                return;
            }
            sink.onDispose(() -> calls.scheduler().schedule(() -> {
                try {
                    watch.close();
                } catch (Exception e) {
                    // already stopping; the subscriber has gone
                }
            }));
        });
    }

    public <T> T unwrap(Class<T> nativeType) {
        return store.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveParameterStore[" + store + "]";
    }
}
