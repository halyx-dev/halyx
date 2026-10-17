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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link Subscription}, from {@link Subscription#async()}: receives as
 * {@code CompletableFuture}s, with no thread waiting out the long poll where the provider's driver has a non-blocking
 * path. Claim-checked bodies are fetched through the blob store's own asynchronous view. Settle the messages with
 * {@link ReceivedMessage#ackAsync()}, {@link ReceivedMessage#nackAsync(Duration)} and
 * {@link ReceivedMessage#extendLeaseAsync(Duration)}, non-blocking there too.
 */
public interface AsyncSubscription {

    String provider();

    String name();

    /** {@link Subscription#receive}: up to {@code max} messages, waiting up to {@code wait} for the first. */
    CompletableFuture<List<ReceivedMessage>> receive(int max, Duration wait);

    /** The blocking subscription this is a view of, for listening. */
    Subscription blocking();

    /** A view that runs each of {@code subscription}'s blocking receives on {@code executor}. */
    static AsyncSubscription onExecutor(Subscription subscription, Executor executor) {
        Objects.requireNonNull(subscription, "subscription");
        Objects.requireNonNull(executor, "executor");
        return new AsyncSubscription() {
            @Override
            public String provider() {
                return subscription.provider();
            }

            @Override
            public String name() {
                return subscription.name();
            }

            @Override
            public CompletableFuture<List<ReceivedMessage>> receive(int max, Duration wait) {
                return CompletableFuture.supplyAsync(() -> subscription.receive(max, wait), executor);
            }

            @Override
            public Subscription blocking() {
                return subscription;
            }
        };
    }
}
