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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.NotFoundException;
import dev.halyx.events.CloudEvent;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.PublishResult;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.TableConfig;
import dev.halyx.storage.Blob;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.GetOptions;
import dev.halyx.storage.PutOptions;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamProducerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Blob bodies as {@code Flux<ByteBuffer>}, and the {@code Flux}-taking batch writes. */
class ReactiveBodiesAndBatchesTest {

    private Halyx halyx;
    private ReactiveHalyx rx;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
    }

    @AfterEach
    void stop() {
        rx.close();
        halyx.close();
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static byte[] join(List<ByteBuffer> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        chunks.forEach(c -> {
            byte[] b = new byte[c.remaining()];
            c.get(b);
            out.writeBytes(b);
        });
        return out.toByteArray();
    }

    @Test
    void bodiesAreReadInChunksAsAskedAndClosedOnCancel() {
        BlobStore memory = halyx.provider("mem").blobStore("b");
        byte[] content = random(300 * 1024);
        memory.put(BlobKey.of("big"), BlobContent.ofBytes(content));
        AtomicLong read = new AtomicLong();
        AtomicBoolean closed = new AtomicBoolean();
        BlobStore watched = (BlobStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {BlobStore.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(memory, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (result instanceof Blob blob) {
                        return new Blob(blob.metadata(), new FilterInputStream(blob.content()) {
                            @Override
                            public int read(byte[] b, int off, int len) throws IOException {
                                int n = super.read(b, off, len);
                                read.addAndGet(Math.max(n, 0));
                                return n;
                            }

                            @Override
                            public void close() throws IOException {
                                closed.set(true);
                                super.close();
                            }
                        });
                    }
                    return result;
                });
        ReactiveBlobStore store = rx.wrap(watched);

        StepVerifier.create(store.getBody(BlobKey.of("big")), 1)
                .expectNextCount(1)
                .then(() -> assertThat(read.get()).as("one chunk read for one requested").isLessThanOrEqualTo(2L * 64 * 1024))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
        assertThat(closed).as("closed on cancel").isTrue();

        closed.set(false);
        AtomicBoolean closedOnCompletion = new AtomicBoolean();
        List<ByteBuffer> chunks = store.getBody(BlobKey.of("big"))
                .doOnComplete(() -> closedOnCompletion.set(closed.get())).collectList().block();
        assertThat(chunks).hasSize(5);
        assertThat(join(chunks)).isEqualTo(content);
        assertThat(closedOnCompletion).as("closed before the completion reaches the subscriber").isTrue();

        assertThat(join(store.getBody(BlobKey.of("big"), GetOptions.defaults(), 1000).collectList().block())).isEqualTo(content);
        StepVerifier.create(store.getBody(BlobKey.of("missing"))).verifyError(NotFoundException.class);
    }

    @Test
    void bodiesAreUploadedAsTheyArriveAndAbortedOnError() {
        ReactiveBlobStore store = rx.provider("mem").blobStore("b");
        byte[] content = random(1024 * 1024);
        Flux<ByteBuffer> chunks = Flux.range(0, 128).map(i -> ByteBuffer.wrap(content, i * 8192, 8192));

        StepVerifier.create(store.put(BlobKey.of("uploaded"), chunks, PutOptions.defaults()))
                .assertNext(result -> assertThat(result.key()).isEqualTo(BlobKey.of("uploaded"))).verifyComplete();
        assertThat(store.getBytes(BlobKey.of("uploaded")).block()).isEqualTo(content);

        Flux<ByteBuffer> failing = chunks.take(10).concatWith(Flux.error(new IllegalStateException("source failed")));
        StepVerifier.create(store.put(BlobKey.of("aborted"), failing, PutOptions.defaults()))
                .verifyErrorMatches(e -> String.valueOf(e.getMessage()).contains("source failed")
                        || (e.getCause() != null && "source failed".equals(e.getCause().getMessage())));
        StepVerifier.create(store.head(BlobKey.of("aborted"))).as("nothing stored").verifyComplete();
    }

    @Test
    void messagesArePublishedInOrderedBatchesAsTheyArrive() {
        dev.halyx.messaging.Subscription inbox = halyx.provider("mem").subscription(
                SubscriptionConfig.builder("orders-sub").topic("orders").build());
        Publisher real = halyx.provider("mem").publisher("orders");
        AtomicInteger requests = new AtomicInteger();
        List<Integer> batchSizes = new ArrayList<>();
        Publisher counting = (Publisher) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Publisher.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("publishAll")) {
                        requests.incrementAndGet();
                        batchSizes.add(((List<?>) args[0]).size());
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AtomicLong maxOutstanding = new AtomicLong();
        AtomicLong outstanding = new AtomicLong();

        List<PublishResult> results = rx.wrap(counting)
                .publishAll(Flux.range(0, 2000)
                        .doOnRequest(n -> maxOutstanding.accumulateAndGet(outstanding.addAndGet(Math.min(n, 1_000_000)), Math::max))
                        .doOnNext(i -> outstanding.decrementAndGet())
                        .map(i -> OutgoingMessage.of("m-" + i)), 100, Duration.ofSeconds(1))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(results).hasSize(2000);
        assertThat(batchSizes).hasSize(20).containsOnly(100);
        assertThat(maxOutstanding.get()).as("demand on the source stays a few batches, not the whole source")
                .isLessThanOrEqualTo(500L);
        List<String> bodies = new ArrayList<>();
        List<ReceivedMessage> received;
        while (!(received = inbox.receive(100, Duration.ofMillis(200))).isEmpty()) {
            received.forEach(m -> {
                bodies.add(m.bodyAsString());
                m.ack();
            });
        }
        assertThat(bodies).as("in order").containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 2000).mapToObj(i -> "m-" + i).toList());
    }

    @Test
    void partialBatchesAreFlushedAfterTheWait() {
        ReactiveEventPublisher bus = rx.provider("mem").eventPublisher("events");
        dev.halyx.messaging.Subscription inbox = halyx.provider("mem").subscription(
                SubscriptionConfig.builder("events-sub").topic("events").build());

        StepVerifier.create(bus.publishAll(Flux.just(CloudEvent.builder("t", "/s").build())
                        .concatWith(Flux.never()), 100, Duration.ofMillis(100)))
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(inbox.receive(10, Duration.ofSeconds(1))).hasSize(1);
    }

    @Test
    void recordsAndDocumentsStreamInBatches() {
        StreamConfig cluster = StreamConfig.builder("memory:9092").build();
        ReactiveStreamProducer producer = rx.provider("mem").streamProducer(StreamProducerConfig.of(cluster, "ledger"));
        StepVerifier.create(producer.sendAll(Flux.range(0, 30).map(i -> OutgoingRecord.of("k" + (i % 3), "v" + i))))
                .expectNextCount(30).verifyComplete();

        ReactiveDocumentStore docs = rx.provider("mem").documentStore(TableConfig.builder("t").sortKey("sk").build());
        List<Key> written = docs.putAll(Flux.range(0, 120).map(i -> Map.entry(Key.of("p", String.format("%03d", i)),
                Document.of(Map.of("n", i))))).collectList().block();
        assertThat(written).hasSize(120).first().isEqualTo(Key.of("p", "000"));
        assertThat(docs.get(Key.of("p", "119")).block()).isNotNull();
    }
}
