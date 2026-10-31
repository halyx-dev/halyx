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
package dev.halyx.tck;

import dev.halyx.CloudContext;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.json.Json;
import dev.halyx.memory.InMemoryFunctions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link FunctionConformanceTest} function for the memory provider. A status stands in as the exception the
 * status maps to, as the memory provider passes a handler's {@code CloudException} through.
 */
public final class TckFunction {

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    private TckFunction() {
    }

    /** Serves the function as {@code name} on {@code context}'s memory connection. */
    public static void register(CloudContext context, String name) {
        TckFunction function = new TckFunction();
        context.functionInvoker(name).unwrap(InMemoryFunctions.class).register(name, function::handle);
    }

    private FunctionResponse handle(FunctionRequest request) {
        Map<String, Object> payload = Json.parseObject(request.payloadAsString()).orElse(Map.of());
        return switch (String.valueOf(payload.get("action"))) {
            case "echo" -> FunctionResponse.of(Json.write(Map.of("body", request.payloadAsString(),
                    "headers", request.headers())));
            case "fail" -> throw new IllegalStateException(String.valueOf(payload.get("message")));
            case "status" -> throw status(((Number) payload.get("status")).intValue());
            case "sleep" -> {
                try {
                    Thread.sleep(((Number) payload.get("millis")).longValue());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield FunctionResponse.of("{}");
            }
            case "count" -> {
                counts.computeIfAbsent(String.valueOf(payload.get("id")), id -> new AtomicLong()).incrementAndGet();
                if (Boolean.TRUE.equals(payload.get("fail"))) {
                    throw new IllegalStateException("halyx-tck counted failure");
                }
                yield FunctionResponse.of("{}");
            }
            case "read" -> FunctionResponse.of(Json.write(Map.of("count",
                    counts.getOrDefault(String.valueOf(payload.get("id")), new AtomicLong()).get())));
            default -> throw new IllegalArgumentException("Unknown action in " + request.payloadAsString());
        };
    }

    private static RuntimeException status(int status) {
        String message = "halyx-tck status " + status;
        return switch (status) {
            case 400 -> new InvalidArgumentException(message, null, "memory", "400");
            case 404 -> new NotFoundException(message, null, "memory", "404");
            default -> new UnknownCloudException(message, null, "memory", String.valueOf(status));
        };
    }
}
