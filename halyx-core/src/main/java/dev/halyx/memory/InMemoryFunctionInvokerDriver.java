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
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnavailableException;
import dev.halyx.functions.FunctionCapability;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.functions.spi.AbstractFunctionInvokerDriver;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Invokes a handler registered with {@link InMemoryFunctions}, on a virtual thread of its own, within the
 * configuration's timeout. The limits are the tightest of the clouds' (Lambda's), so code tested here fits on all
 * three.
 */
final class InMemoryFunctionInvokerDriver extends AbstractFunctionInvokerDriver {

    private static final Capabilities CAPABILITIES = Capabilities.of(FunctionCapability.values());
    private static final long MAX_PAYLOAD = 6L * 1024 * 1024;
    private static final long MAX_TRIGGER_PAYLOAD = 1024L * 1024;
    private static final long MAX_HEADERS = 3_583;

    private final FunctionConfig config;
    private final InMemoryFunctions functions;

    InMemoryFunctionInvokerDriver(FunctionConfig config, InMemoryFunctions functions) {
        super(InMemoryProvider.ID);
        this.config = config;
        this.functions = functions;
    }

    @Override
    public String function() {
        return config.name();
    }

    @Override
    public FunctionResponse invoke(FunctionRequest request) {
        Function<FunctionRequest, FunctionResponse> handler = handler();
        FutureTask<FunctionResponse> run = new FutureTask<>(() -> handler.apply(request));
        Thread.ofVirtual().name("halyx-function-" + config.name()).start(run);
        try {
            return run.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            run.cancel(true);
            throw new UnavailableException("Function " + config.name() + " did not respond within " + config.timeout(),
                    e, provider, null);
        } catch (InterruptedException e) {
            run.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted invoking function " + config.name(), e, provider, null);
        } catch (ExecutionException e) {
            throw failed(e.getCause());
        }
    }

    /** Runs the handler on a virtual thread and returns at once; what it returns or throws is dropped. */
    @Override
    public void trigger(FunctionRequest request) {
        Function<FunctionRequest, FunctionResponse> handler = handler();
        Thread.ofVirtual().name("halyx-function-" + config.name()).start(() -> {
            try {
                handler.apply(request);
            } catch (RuntimeException ignored) {
                // A triggered function's failure isn't reported to the caller, as on Lambda.
            }
        });
    }

    private Function<FunctionRequest, FunctionResponse> handler() {
        return functions.handler(config.name()).orElseThrow(() -> new NotFoundException(
                "No function " + config.name(), null, provider, null));
    }

    private CloudException failed(Throwable error) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return new UnavailableException("Function " + config.name() + " failed: " + error.getMessage(), error,
                provider, error.getClass().getName());
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public long maxPayloadBytes() {
        return MAX_PAYLOAD;
    }

    @Override
    public long maxTriggerPayloadBytes() {
        return MAX_TRIGGER_PAYLOAD;
    }

    @Override
    public long maxHeaderBytes() {
        return MAX_HEADERS;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(functions);
    }
}
