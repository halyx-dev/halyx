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
import dev.halyx.messaging.AsyncPublisher;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.PublishResult;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** A {@link dev.halyx.messaging.Publisher} as {@code Mono}s, each publishing once per subscription on the application's scheduler. */
public final class ReactivePublisher {

    private final dev.halyx.messaging.Publisher publisher;
    private final Calls calls;

    ReactivePublisher(dev.halyx.messaging.Publisher publisher, Calls calls) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.calls = calls;
    }

    public String provider() {
        return publisher.provider();
    }

    public String topic() {
        return publisher.topic();
    }

    public Capabilities capabilities() {
        return publisher.capabilities();
    }

    /** The blocking publisher this wraps. */
    public dev.halyx.messaging.Publisher blocking() {
        return publisher;
    }

    /**
     * The publisher's asynchronous view for one call, whose blocking work (where the driver has no non-blocking path)
     * runs on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncPublisher async(Executor blocking) {
        AsyncPublisher view = publisher.async(blocking);
        return view != null ? view : AsyncPublisher.onExecutor(publisher, blocking); // a mock or proxy without one
    }

    public Mono<PublishResult> publish(OutgoingMessage message) {
        return calls.future(blocking -> async(blocking).publish(message));
    }

    public Mono<PublishResult> publish(String body) {
        return calls.future(blocking -> async(blocking).publish(body));
    }

    /** Publishes in as few requests as the provider allows; results in the order of {@code messages}. */
    public Mono<List<PublishResult>> publishAll(List<OutgoingMessage> messages) {
        return calls.future(blocking -> async(blocking).publishAll(messages));
    }

    /**
     * Publishes {@code messages} as they arrive, in batches of up to {@code batchSize} (a partial batch waits
     * at most {@code maxWait}), one batch at a time and in order. Emits each message's result once its batch
     * is published; a failed batch fails the {@code Flux}, and earlier batches stay published.
     */
    public Flux<PublishResult> publishAll(Publisher<OutgoingMessage> messages, int batchSize, Duration maxWait) {
        return calls.batches(messages, batchSize, maxWait, batch -> () -> publisher.publishAll(batch));
    }

    /** {@link #publishAll(Publisher, int, Duration)} with batches of 100, waiting at most 50 ms. */
    public Flux<PublishResult> publishAll(Publisher<OutgoingMessage> messages) {
        return publishAll(messages, ReactiveHalyx.DEFAULT_BATCH_SIZE, ReactiveHalyx.DEFAULT_BATCH_WAIT);
    }

    public <T> T unwrap(Class<T> nativeType) {
        return publisher.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactivePublisher[" + publisher + "]";
    }
}
