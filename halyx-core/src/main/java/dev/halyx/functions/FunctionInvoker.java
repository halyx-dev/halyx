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
import dev.halyx.interceptor.InterceptorChain;

import java.util.concurrent.Executor;

/**
 * Invokes a function: an AWS Lambda function, an Azure function or a Cloud Run function, with the cloud's login. A
 * call either returns the function's response or throws a {@code CloudException}: a missing function raises
 * {@code NotFoundException} and a function that throws raises {@code UnavailableException}, on every cloud.
 * Thread-safe.
 */
public interface FunctionInvoker {

    String provider();

    /** The function this invokes. */
    String function();

    /** Invokes the function and waits for its response. */
    FunctionResponse invoke(FunctionRequest request);

    /**
     * Starts the function without waiting for it to run (requires {@link FunctionCapability#TRIGGER}; headers
     * require {@link FunctionCapability#TRIGGER_HEADERS}). Returns once the cloud has accepted the invocation.
     */
    void trigger(FunctionRequest request);

    Capabilities capabilities();

    /**
     * The asynchronous view of this invoker, whose calls return {@code CompletableFuture}s; non-blocking where the
     * provider's driver has a non-blocking path. Cheap to call: Halyx' invokers keep one.
     */
    default AsyncFunctionInvoker async() {
        return async(InterceptorChain.blocking());
    }

    /** The asynchronous view, running any blocking work on {@code blocking}, such as an application's scheduler. */
    default AsyncFunctionInvoker async(Executor blocking) {
        return AsyncFunctionInvoker.onExecutor(this, blocking);
    }

    /** The native client, such as {@code LambdaClient}. */
    <T> T unwrap(Class<T> nativeType);
}
