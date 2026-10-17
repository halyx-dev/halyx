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
package dev.halyx.messaging;

import dev.halyx.capability.Capabilities;
import dev.halyx.interceptor.InterceptorChain;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

/** Consumes from one subscription or queue: pull with {@link #receive}, or run a listener with {@link #listen}. */
public interface Subscription {

    String provider();

    String name();

    Capabilities capabilities();

    /**
     * Up to {@code max} messages, waiting up to {@code wait} for one to arrive. Each must be acked, nacked or
     * left to its lease running out, after which it is redelivered.
     */
    List<ReceivedMessage> receive(int max, Duration wait);

    /** Runs {@code listener} on every message until the returned consumer is closed. */
    /**
     * The asynchronous view of this subscription, whose receives return {@code CompletableFuture}s, with no thread
     * waiting out the long poll where the provider's driver has a non-blocking path. Cheap to call.
     */
    default AsyncSubscription async() {
        return async(InterceptorChain.blocking());
    }

    /** The asynchronous view, running any blocking work on {@code blocking}, such as an application's scheduler. */
    default AsyncSubscription async(Executor blocking) {
        return AsyncSubscription.onExecutor(this, blocking);
    }

    MessageConsumer listen(MessageListener listener, ConsumerOptions options);

    default MessageConsumer listen(MessageListener listener) {
        return listen(listener, ConsumerOptions.DEFAULTS);
    }

    /** How long a received message stays invisible without an extension. */
    Duration defaultLease();

    <T> T unwrap(Class<T> nativeType);
}
