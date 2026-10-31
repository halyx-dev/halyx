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
package dev.halyx.functions.spi;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A provider's function invoker. Requests reach it with the configured headers merged in, and within its limits and
 * capabilities; the driver applies the configuration's timeout and login.
 */
public interface FunctionInvokerDriver extends AutoCloseable {

    String provider();

    String function();

    /** Invokes the function and returns its response; throws if the function failed. */
    FunctionResponse invoke(FunctionRequest request);

    /** Starts the function without waiting for it; called only when {@code TRIGGER} is declared. */
    default void trigger(FunctionRequest request) {
        throw new UnsupportedFeatureException("Function triggers are not supported");
    }

    /**
     * {@link #invoke} without blocking, where the SDK allows it; by default the blocking call on the blocking
     * executor. Failures may be returned or thrown; the API layer translates both.
     */
    default CompletableFuture<FunctionResponse> invokeAsync(FunctionRequest request, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> invoke(request), blocking);
    }

    /** {@link #trigger} without blocking, where the SDK allows it; by default the blocking call on the executor. */
    default CompletableFuture<Void> triggerAsync(FunctionRequest request, Executor blocking) {
        return CompletableFuture.runAsync(() -> trigger(request), blocking);
    }

    Capabilities capabilities();

    /** Largest payload {@link #invoke} accepts (Lambda: 6 MB). */
    long maxPayloadBytes();

    /** Largest payload {@link #trigger} accepts (Lambda: 1 MB); unused without {@code TRIGGER}. */
    default long maxTriggerPayloadBytes() {
        return maxPayloadBytes();
    }

    /** Largest {@link #headerBytes} a request may have; {@code Long.MAX_VALUE} leaves the check to the cloud. */
    default long maxHeaderBytes() {
        return Long.MAX_VALUE;
    }

    /**
     * The size of {@code headers} as the limit counts it; by default their names and values in UTF-8. Lambda counts
     * the encoded {@code ClientContext} that carries them.
     */
    default long headerBytes(Map<String, String> headers) {
        long size = 0;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            size += header.getKey().getBytes(StandardCharsets.UTF_8).length
                    + header.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
