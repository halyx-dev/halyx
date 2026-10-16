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
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.TableConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reactive services from other reactive libraries, through Reactive Streams: RxJava 3, SmallRye Mutiny and
 * the JDK's {@code Flow}, plus {@code CompletableFuture}. Values, errors, completion and back-pressure carry over.
 */
class InteroperabilityTest {

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

    private ReactiveDocumentStore orders() {
        ReactiveDocumentStore orders = rx.provider("mem").documentStore(TableConfig.builder("orders").sortKey("sk").build());
        IntStream.range(0, 30).forEach(i -> orders.blocking().put(Key.of("c-1", String.format("%02d", i)), Document.of(Map.of("n", i))));
        return orders;
    }

    @Test
    void rxJava() {
        ReactiveBlobStore blobs = rx.provider("mem").blobStore("b");
        Single.fromPublisher(blobs.put(BlobKey.of("k"), BlobContent.ofString("v"))).blockingGet();

        assertThat(new String(Single.fromPublisher(blobs.getBytes(BlobKey.of("k"))).blockingGet())).isEqualTo("v");
        assertThatThrownBy(() -> Single.fromPublisher(blobs.getBytes(BlobKey.of("missing"))).blockingGet())
                .isInstanceOf(NotFoundException.class);
        List<String> firstFive = Flowable.fromPublisher(orders().queryAll(Query.partition("c-1")))
                .map(item -> item.key().sort().orElseThrow()).take(5).toList().blockingGet();
        assertThat(firstFive).containsExactly("00", "01", "02", "03", "04");
    }

    @Test
    void mutiny() {
        ReactiveBlobStore blobs = rx.provider("mem").blobStore("b");
        // Mutiny 2 speaks java.util.concurrent.Flow: Reactor's JdkFlowAdapter converts both ways.
        Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(blobs.put(BlobKey.of("k"), BlobContent.ofString("v"))))
                .await().atMost(Duration.ofSeconds(5));

        assertThat(Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(blobs.head(BlobKey.of("missing"))))
                .await().atMost(Duration.ofSeconds(5))).as("an empty Mono is a null item").isNull();
        List<String> all = Multi.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(orders().queryAll(Query.partition("c-1"))))
                .map(item -> item.key().sort().orElseThrow()).collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(all).hasSize(30);

        // Into Halyx: a Multi of messages published in batches.
        rx.provider("mem").subscription(SubscriptionConfig.builder("orders-sub").topic("orders").build());
        Long published = rx.provider("mem").publisher("orders")
                .publishAll(JdkFlowAdapter.flowPublisherToFlux(Multi.createFrom().range(0, 12).map(i -> OutgoingMessage.of("m-" + i))),
                        5, Duration.ofMillis(50))
                .count().block(Duration.ofSeconds(5));
        assertThat(published).isEqualTo(12);
    }

    @Test
    void jdkFlowWithBackPressure() throws Exception {
        Flow.Publisher<dev.halyx.nosql.Item> items = JdkFlowAdapter.publisherToFlowPublisher(orders().queryAll(Query.partition("c-1")));
        List<String> received = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        items.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(dev.halyx.nosql.Item item) {
                received.add(item.key().sort().orElseThrow());
                if (received.size() == 3) {
                    subscription.cancel();
                    done.complete(null);
                } else {
                    subscription.request(1);
                }
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });

        done.get();
        assertThat(received).containsExactly("00", "01", "02");
    }

    @Test
    void completableFutures() {
        ReactiveBlobStore blobs = rx.provider("mem").blobStore("b");
        CompletableFuture<Boolean> exists = blobs.exists(BlobKey.of("k")).toFuture();
        assertThat(exists.join()).isFalse();
        CompletableFuture<byte[]> missing = blobs.getBytes(BlobKey.of("missing")).toFuture();
        assertThatThrownBy(missing::join).hasCauseInstanceOf(NotFoundException.class);

        // A deferred future starts the call only when subscribed, as a Mono does.
        Mono<byte[]> lazy = blobs.getBytes(BlobKey.of("later"));
        blobs.put(BlobKey.of("later"), BlobContent.ofString("now")).block();
        assertThat(new String(lazy.toFuture().join())).isEqualTo("now");
        assertThat(blobs.blocking().exists(BlobKey.of("later"))).isTrue();
    }
}
