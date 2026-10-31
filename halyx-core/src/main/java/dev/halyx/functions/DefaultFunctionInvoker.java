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
package dev.halyx.functions;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.spi.FunctionInvokerDriver;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The portable function invoker: merges the configured headers, checks capabilities and limits before anything is
 * sent, and runs the driver through the interceptors.
 */
public final class DefaultFunctionInvoker implements FunctionInvoker {

    static final String SERVICE = "functions";

    private final FunctionInvokerDriver driver;
    private final FunctionConfig config;
    private final InterceptorChain interceptors;
    private final AsyncFunctionInvoker async = new Async(InterceptorChain.blocking());

    public DefaultFunctionInvoker(FunctionInvokerDriver driver, FunctionConfig config, InterceptorChain interceptors) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.config = Objects.requireNonNull(config, "config");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public String function() {
        return driver.function();
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public FunctionResponse invoke(FunctionRequest request) {
        FunctionRequest merged = checkInvoke(request);
        return interceptors.execute(context("invoke"), () -> {
            try {
                return driver.invoke(merged);
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    @Override
    public void trigger(FunctionRequest request) {
        FunctionRequest merged = checkTrigger(request);
        interceptors.execute(context("trigger"), () -> {
            try {
                driver.trigger(merged);
                return null;
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    /** Retries follow the configuration: a function runs again on a retry, so only idempotent ones are retried. */
    private OperationContext context(String operation) {
        return new OperationContext(SERVICE, operation, provider(), function(), config.idempotent());
    }

    private FunctionRequest checkInvoke(FunctionRequest request) {
        FunctionRequest merged = merge(request);
        checkPayload(merged, driver.maxPayloadBytes(), "invoke");
        checkHeaders(merged);
        return merged;
    }

    private FunctionRequest checkTrigger(FunctionRequest request) {
        FunctionRequest merged = merge(request);
        driver.capabilities().require(FunctionCapability.TRIGGER, provider());
        if (!merged.headers().isEmpty() && !driver.capabilities().supports(FunctionCapability.TRIGGER_HEADERS)) {
            throw new UnsupportedFeatureException("Provider '" + provider() + "' does not support headers on a trigger "
                    + "(TRIGGER_HEADERS); function " + function() + " was given " + merged.headers().keySet(),
                    null, provider(), null);
        }
        checkPayload(merged, driver.maxTriggerPayloadBytes(), "trigger");
        checkHeaders(merged);
        return merged;
    }

    /** The configured headers, then the request's, which replace any with the same name. */
    private FunctionRequest merge(FunctionRequest request) {
        Objects.requireNonNull(request, "request");
        if (config.headers().isEmpty()) {
            return request;
        }
        return FunctionRequest.builder(request.payloadUnsafe()).contentType(request.contentType())
                .headers(config.headers()).headers(request.headers()).build();
    }

    private void checkPayload(FunctionRequest request, long limit, String operation) {
        if (request.size() > limit) {
            throw new InvalidArgumentException("The " + operation + " payload for function " + function() + " is "
                    + request.size() + " bytes, over the " + limit + "-byte limit of " + provider(), null, provider(), null);
        }
    }

    private void checkHeaders(FunctionRequest request) {
        long size = driver.headerBytes(request.headers());
        if (size > driver.maxHeaderBytes()) {
            throw new InvalidArgumentException("The headers for function " + function() + " are " + size
                    + " bytes, over the " + driver.maxHeaderBytes() + "-byte limit of " + provider(), null, provider(), null);
        }
    }

    @Override
    public AsyncFunctionInvoker async() {
        return async;
    }

    @Override
    public AsyncFunctionInvoker async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    /** The asynchronous view: the same checks, then the driver's non-blocking path. */
    private final class Async implements AsyncFunctionInvoker {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultFunctionInvoker.this.provider();
        }

        @Override
        public String function() {
            return DefaultFunctionInvoker.this.function();
        }

        @Override
        public CompletableFuture<FunctionResponse> invoke(FunctionRequest request) {
            FunctionRequest merged;
            try {
                merged = checkInvoke(request);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return call("invoke", () -> driver.invokeAsync(merged, blocking));
        }

        @Override
        public CompletableFuture<Void> trigger(FunctionRequest request) {
            FunctionRequest merged;
            try {
                merged = checkTrigger(request);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return call("trigger", () -> driver.triggerAsync(merged, blocking));
        }

        private <T> CompletableFuture<T> call(String operation, Supplier<CompletableFuture<T>> send) {
            return interceptors.executeAsync(context(operation), () -> {
                CompletableFuture<T> sent;
                try {
                    sent = send.get();
                } catch (RuntimeException e) {
                    return CompletableFuture.<T>failedFuture(driver.translate(e));
                }
                return sent.exceptionallyCompose(error -> {
                    Throwable cause = AsyncInvocation.unwrap(error);
                    return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
                });
            }, blocking);
        }

        @Override
        public FunctionInvoker blocking() {
            return DefaultFunctionInvoker.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultFunctionInvoker.this;
        }
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "FunctionInvoker[" + provider() + ":" + function() + "]";
    }
}
