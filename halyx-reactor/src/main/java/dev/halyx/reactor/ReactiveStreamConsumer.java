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
import dev.halyx.stream.AsyncStreamConsumer;
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamListener;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * A {@link StreamConsumer} as a {@code Flux} of records, plus {@code Mono}s for polling and committing by hand.
 *
 * <pre>{@code
 * billing.process(ListenerOptions.DEFAULTS, record -> bill(record))   // completes: committed; fails: retried
 *        .subscribe();
 * }</pre>
 *
 * Records are handled as the consumer's listener handles them: one at a time per partition, in order, so a
 * record that fails is retried after the backoff, holding back the rest of its partition, until
 * {@code maxAttempts} sends it to the dead letter. Other partitions carry on. Only one listener or
 * {@code Flux} uses a consumer at a time: a {@code Flux} subscribed while another runs starts once that one has
 * ended and its listener has stopped, so {@code retry()} and {@code repeat()} carry on where it left off.
 *
 * <p>Cancelling a {@code Flux} stops reading but keeps this member in its group, with its partitions, so a new
 * subscriber carries on where it left off; no other member reads those partitions meanwhile. {@link #close()}
 * leaves the group, handing them on.
 */
public final class ReactiveStreamConsumer {

    private final StreamConsumer consumer;
    private final Calls calls;
    /** Completes once the listener of the latest {@code Flux} has stopped; guarded by this object's lock. */
    private CompletableFuture<Void> stopped = CompletableFuture.completedFuture(null);

    ReactiveStreamConsumer(StreamConsumer consumer, Calls calls) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.calls = calls;
    }

    /** The consumer's asynchronous view for one call, running on {@code blocking}: the scheduler, context restored. */
    private AsyncStreamConsumer async(Executor blocking) {
        AsyncStreamConsumer view = consumer.async(blocking);
        return view != null ? view : AsyncStreamConsumer.onExecutor(consumer, blocking); // a mock or proxy without one
    }

    public String provider() {
        return consumer.provider();
    }

    public String topic() {
        return consumer.topic();
    }

    public String group() {
        return consumer.group();
    }

    public Capabilities capabilities() {
        return consumer.capabilities();
    }

    /** The blocking consumer this wraps. */
    public StreamConsumer blocking() {
        return consumer;
    }

    /**
     * Records as they arrive: {@link Delivery#ack()} marks one handled, so its offset is committed, and
     * {@link Delivery#nack} retries it. What is unsettled when the {@code Flux} is cancelled is not committed,
     * and is read again by the group.
     */
    public Flux<Delivery<ReceivedRecord>> records(ListenerOptions options) {
        return Flux.defer(() -> {
            CompletableFuture<Void> previous;
            CompletableFuture<Void> mine = new CompletableFuture<>();
            synchronized (this) {
                previous = stopped;
                stopped = mine;
            }
            // Started once, by the listener or by the end of a Flux that never got that far.
            AtomicBoolean started = new AtomicBoolean();
            // A Flux ending stops its listener in the background; the next one, as from retry() or repeat(), waits
            // for that, since the consumer runs one listener at a time. So does one subscribed meanwhile.
            return Mono.fromFuture(previous, true)
                    // A record left unsettled by a cancelled pipeline fails its listener, so it isn't committed.
                    .thenMany(Deliveries.<ReceivedRecord>flux(calls.scheduler(), listener -> {
                        if (!started.compareAndSet(false, true)) {
                            throw new IllegalStateException(this + ": the Flux ended before its listener started");
                        }
                        StreamListener running;
                        try {
                            running = consumer.listen(listener::onDelivery, options);
                        } catch (RuntimeException e) {
                            mine.complete(null);
                            throw e;
                        }
                        return () -> {
                            try {
                                running.close();
                            } finally {
                                mine.complete(null);
                            }
                        };
                    }, null))
                    .doFinally(signal -> {
                        if (started.compareAndSet(false, true)) {
                            mine.complete(null);
                        }
                    });
        });
    }

    /**
     * Runs {@code handler} for each record: completing commits it, failing retries it. A handler's values are
     * emitted once it has completed and its record is marked handled. Records of one partition are handled one
     * at a time, in order.
     */
    public <R> Flux<R> process(ListenerOptions options, Function<ReceivedRecord, ? extends Publisher<? extends R>> handler) {
        return Deliveries.process(records(options), Integer.MAX_VALUE, handler);
    }

    /** Records read within {@code wait}, for consumers driven by hand; commit with {@link #commit()}. */
    public Mono<List<ReceivedRecord>> poll(Duration wait) {
        return calls.future(blocking -> async(blocking).poll(wait));
    }

    /** Commits the positions of the records polled so far. */
    public Mono<Void> commit() {
        return calls.future(blocking -> async(blocking).commit()).then();
    }

    /** Stops any listener or {@code Flux} and leaves the group, so other members take over its partitions. */
    public Mono<Void> close() {
        return calls.run(consumer::close);
    }

    public <T> T unwrap(Class<T> nativeType) {
        return consumer.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveStreamConsumer[" + consumer + "]";
    }
}
