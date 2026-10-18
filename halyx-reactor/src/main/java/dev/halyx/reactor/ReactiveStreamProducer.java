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
import dev.halyx.stream.AsyncStreamProducer;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.SendResult;
import dev.halyx.stream.StreamProducer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** A {@link StreamProducer} as {@code Mono}s, each sending once per subscription on the application's scheduler. */
public final class ReactiveStreamProducer {

    private final StreamProducer producer;
    private final Calls calls;

    ReactiveStreamProducer(StreamProducer producer, Calls calls) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.calls = calls;
    }

    public String provider() {
        return producer.provider();
    }

    public String topic() {
        return producer.topic();
    }

    public Capabilities capabilities() {
        return producer.capabilities();
    }

    /** The blocking producer this wraps. */
    public StreamProducer blocking() {
        return producer;
    }

    /**
     * The producer's asynchronous view for one call, whose blocking work (a transaction, or a driver without a
     * non-blocking path) runs on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncStreamProducer async(Executor blocking) {
        AsyncStreamProducer view = producer.async(blocking);
        return view != null ? view : AsyncStreamProducer.onExecutor(producer, blocking); // a mock or proxy without one
    }

    public Mono<SendResult> send(OutgoingRecord record) {
        return calls.future(blocking -> async(blocking).send(record));
    }

    public Mono<SendResult> send(String key, String value) {
        return calls.future(blocking -> async(blocking).send(key, value));
    }

    /** Sends the records, results in their order. */
    public Mono<List<SendResult>> sendAll(List<OutgoingRecord> records) {
        return calls.future(blocking -> async(blocking).sendAll(records));
    }

    /**
     * Sends {@code records} as they arrive, in batches of up to {@code batchSize} (a partial batch waits at
     * most {@code maxWait}), one batch at a time, so records keep their order. Emits each record's result once
     * its batch is sent; a failed batch fails the {@code Flux}.
     */
    public Flux<SendResult> sendAll(Publisher<OutgoingRecord> records, int batchSize, Duration maxWait) {
        return calls.batches(records, batchSize, maxWait, batch -> () -> producer.sendAll(batch));
    }

    /** {@link #sendAll(Publisher, int, Duration)} with batches of 100, waiting at most 50 ms. */
    public Flux<SendResult> sendAll(Publisher<OutgoingRecord> records) {
        return sendAll(records, ReactiveHalyx.DEFAULT_BATCH_SIZE, ReactiveHalyx.DEFAULT_BATCH_WAIT);
    }

    public Mono<Integer> partitionCount() {
        return calls.mono(producer::partitionCount);
    }

    public <T> T unwrap(Class<T> nativeType) {
        return producer.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveStreamProducer[" + producer + "]";
    }
}
