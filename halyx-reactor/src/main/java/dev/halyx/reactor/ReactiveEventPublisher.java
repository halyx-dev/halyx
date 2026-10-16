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

import dev.halyx.events.CloudEvent;
import dev.halyx.events.EventPublisher;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** An {@link EventPublisher} as {@code Mono}s, each publishing once per subscription on the application's scheduler. */
public final class ReactiveEventPublisher {

    private final EventPublisher publisher;
    private final Calls calls;

    ReactiveEventPublisher(EventPublisher publisher, Calls calls) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.calls = calls;
    }

    public String provider() {
        return publisher.provider();
    }

    public String bus() {
        return publisher.bus();
    }

    /** The blocking publisher this wraps. */
    public EventPublisher blocking() {
        return publisher;
    }

    /** Publishes the event; resubscribing after a failure publishes it again with the same id. */
    public Mono<Void> publish(CloudEvent event) {
        return calls.run(() -> publisher.publish(event));
    }

    /** Publishes in as few requests as the router allows; not atomic, see {@link EventPublisher#publishAll}. */
    public Mono<Void> publishAll(List<CloudEvent> events) {
        return calls.run(() -> publisher.publishAll(events));
    }

    /**
     * Publishes {@code events} as they arrive, in batches of up to {@code batchSize} (a partial batch waits at
     * most {@code maxWait}), one batch at a time and in order. Emits each event once its batch is published;
     * a failed batch fails the {@code Flux}, and earlier batches stay published.
     */
    public Flux<CloudEvent> publishAll(Publisher<CloudEvent> events, int batchSize, Duration maxWait) {
        return calls.batches(events, batchSize, maxWait, batch -> () -> {
            publisher.publishAll(batch);
            return batch;
        });
    }

    /** {@link #publishAll(Publisher, int, Duration)} with batches of 100, waiting at most 50 ms. */
    public Flux<CloudEvent> publishAll(Publisher<CloudEvent> events) {
        return publishAll(events, ReactiveHalyx.DEFAULT_BATCH_SIZE, ReactiveHalyx.DEFAULT_BATCH_WAIT);
    }

    public <T> T unwrap(Class<T> nativeType) {
        return publisher.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveEventPublisher[" + publisher + "]";
    }
}
