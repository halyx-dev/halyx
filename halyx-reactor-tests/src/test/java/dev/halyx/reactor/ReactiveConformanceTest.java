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

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.Codec;
import dev.halyx.config.ProviderConfig;
import dev.halyx.events.EventPublisher;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.messaging.Subscription;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.tck.BlobStoreConformanceTest;
import dev.halyx.tck.CacheConformanceTest;
import dev.halyx.tck.DocumentStoreConformanceTest;
import dev.halyx.tck.EventBusConformanceTest;
import dev.halyx.tck.FunctionConformanceTest;
import dev.halyx.tck.MessagingConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;
import dev.halyx.tck.StreamConformanceTest;
import dev.halyx.tck.TckFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;

import java.net.URI;

/**
 * The conformance kits through the reactive adapters, on the in-memory provider: every call goes through a
 * {@code Mono} or {@code Flux} on a virtual-thread scheduler and is waited for (see {@link BlockingViews}).
 */
class ReactiveConformanceTest {

    private static Halyx halyx;
    private static ReactiveHalyx rx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
    }

    @AfterAll
    static void stop() {
        rx.close();
        halyx.close();
    }

    private static CloudContext context() {
        return halyx.provider("test");
    }

    @Nested
    class Blobs extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return BlockingViews.of(rx.provider("test").blobStore("tck"));
        }
    }

    @Nested
    class Documents extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return BlockingViews.of(rx.provider("test").documentStore(indexes(table, sorted).build()));
        }
    }

    @Nested
    class Messaging extends MessagingConformanceTest {
        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            TopicConfig.Builder topic = TopicConfig.builder(name);
            SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(name + "-sub").topic(name)
                    .property("memory.lease", "PT1S");
            if (claimCheck != null) {
                topic.claimCheck(claimCheck);
                subscription.claimCheck(claimCheck);
            }
            Subscription sub = BlockingViews.of(rx.provider("test").subscription(subscription.build()));
            return new Channel(BlockingViews.of(rx.provider("test").publisher(topic.build())), sub);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return context().blobStore("claims");
        }
    }

    @Nested
    class Events extends EventBusConformanceTest {
        // Subscribed before anything is published, as a rule's queue is.
        private final Subscription inbox = BlockingViews.of(rx.provider("test").subscription(
                SubscriptionConfig.builder("events-inbox").topic("events").build()));

        @Override
        protected EventPublisher publisher() {
            return BlockingViews.of(rx.provider("test").eventPublisher("events"));
        }

        @Override
        protected Subscription inbox() {
            return inbox;
        }
    }

    @Nested
    class Functions extends FunctionConformanceTest {
        Functions() {
            TckFunction.register(context(), "tck");
        }

        @Override
        protected FunctionConfig.Builder function() {
            return FunctionConfig.builder("tck");
        }

        @Override
        protected FunctionConfig.Builder missingFunction() {
            return FunctionConfig.builder("tck-missing");
        }

        @Override
        protected FunctionInvoker invoker(FunctionConfig config) {
            return BlockingViews.of(rx.provider("test").functionInvoker(config));
        }

        @Override
        protected long maxPayloadBytes() {
            return 6L * 1024 * 1024;
        }

        @Override
        protected long maxHeaderBytes() {
            return 3_583;
        }
    }

    @Nested
    class Caches extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return ReactiveConformanceTest.context();
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(URI.create("redis://memory:6379"));
        }

        @Override
        protected <V> Cache<V> newCache(CacheConfig config, Codec<V> codec) {
            return BlockingViews.of(rx.provider("test").cache(config, codec));
        }

        @Override
        protected Cache<String> newCache(CacheConfig config) {
            return BlockingViews.of(rx.provider("test").cache(config));
        }
    }

    @Nested
    class Parameters extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return ReactiveConformanceTest.context();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected ParameterStore newStore(ParameterStoreConfig config) {
            return BlockingViews.of(rx.provider("test").parameterStore(config));
        }
    }

    @Nested
    class Streams extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return ReactiveConformanceTest.context();
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return StreamConfig.builder("memory:9092");
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            // In-memory topics are created on first use.
        }

        @Override
        protected BlobStore claimCheckStore() {
            return ReactiveConformanceTest.context().blobStore("claims");
        }

        @Override
        protected StreamProducer newProducer(StreamProducerConfig config) {
            return BlockingViews.of(rx.provider("test").streamProducer(config));
        }

        @Override
        protected StreamConsumer newConsumer(StreamConsumerConfig config) {
            return BlockingViews.of(rx.provider("test").streamConsumer(config));
        }
    }
}
