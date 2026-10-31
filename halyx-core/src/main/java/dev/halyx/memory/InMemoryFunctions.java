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

import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The memory provider's functions: handlers registered by name, shared by every invoker on the connection. Reach it
 * with {@code invoker.unwrap(InMemoryFunctions.class)}. A function with no handler raises {@code NotFoundException};
 * a handler that throws raises {@code UnavailableException}, as on the clouds, unless it throws a
 * {@code CloudException}, which passes through as it is, standing in for a status such as 400 or 404.
 */
public final class InMemoryFunctions {

    private final Map<String, Function<FunctionRequest, FunctionResponse>> handlers = new ConcurrentHashMap<>();

    InMemoryFunctions() {
    }

    /** Serves {@code function} with {@code handler}, replacing any handler it had. */
    public void register(String function, Function<FunctionRequest, FunctionResponse> handler) {
        handlers.put(Objects.requireNonNull(function, "function"), Objects.requireNonNull(handler, "handler"));
    }

    /** Removes {@code function}'s handler, so invoking it raises {@code NotFoundException}. */
    public void remove(String function) {
        handlers.remove(Objects.requireNonNull(function, "function"));
    }

    Optional<Function<FunctionRequest, FunctionResponse>> handler(String function) {
        return Optional.ofNullable(handlers.get(function));
    }

    void clear() {
        handlers.clear();
    }
}
