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
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.tck.CacheConformanceTest;
import dev.halyx.tck.StreamConformanceTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapters on real infrastructure: the stream kit on Apache Kafka and the cache kit on Valkey, through the
 * reactive services, plus a {@code records} pipeline on a real consumer group. The provider is AWS's, pointed at
 * the containers, as in the AWS module's own stream tests.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReactiveContainersIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(System.getProperty("kafka.image", "apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("valkey.image", "valkey/valkey:9.1.2-alpine")))
            .withExposedPorts(6379);

    private static Halyx halyx;
    private static ReactiveHalyx rx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("aws", ProviderConfig.of("aws").region("us-east-1")
                .credentials(Credentials.of("test", "test"))).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
    }

    @AfterAll
    static void stop() {
        rx.close();
        halyx.close();
    }

    private static CloudContext context() {
        return halyx.provider("aws");
    }

    /** Creates a topic and waits until the broker serves its partitions. */
    static void createTopic(String topic, int partitions) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (int p = 0; p < partitions; p++) {
                latest.put(new TopicPartition(topic, p), OffsetSpec.latest());
            }
            Instant deadline = Instant.now().plusSeconds(30);
            while (true) {
                try {
                    admin.listOffsets(latest).all().get();
                    return;
                } catch (ExecutionException notYetServed) {
                    if (Instant.now().isAfter(deadline)) {
                        throw new IllegalStateException("Partitions of " + topic + " not served after 30s");
                    }
                    Thread.sleep(100);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create topic " + topic, e);
        }
    }

    @Nested
    class StreamsOnKafka extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return ReactiveContainersIT.context();
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return StreamConfig.builder(KAFKA.getBootstrapServers());
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            ReactiveContainersIT.createTopic(topic, partitions);
        }

        @Override
        protected StreamProducer newProducer(StreamProducerConfig config) {
            return BlockingViews.of(rx.provider("aws").streamProducer(config));
        }

        @Override
        protected StreamConsumer newConsumer(StreamConsumerConfig config) {
            return BlockingViews.of(rx.provider("aws").streamConsumer(config));
        }
    }

    @Nested
    class CachesOnValkey extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return ReactiveContainersIT.context();
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(URI.create("redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379)));
        }

        @Override
        protected <V> Cache<V> newCache(CacheConfig config, Codec<V> codec) {
            return BlockingViews.of(rx.provider("aws").cache(config, codec));
        }

        @Override
        protected Cache<String> newCache(CacheConfig config) {
            return BlockingViews.of(rx.provider("aws").cache(config));
        }
    }

    @Test
    void recordsPipelineOnAConsumerGroupCommitsInOrderAndResumesAfterCancel() {
        String topic = "ledger-" + UUID.randomUUID().toString().substring(0, 8);
        createTopic(topic, 3);
        StreamConfig cluster = StreamConfig.builder(KAFKA.getBootstrapServers()).build();
        ReactiveStreamProducer producer = rx.provider("aws").streamProducer(StreamProducerConfig.of(cluster, topic));
        for (int i = 0; i < 60; i++) {
            producer.send(OutgoingRecord.of("account-" + (i % 6), "entry-" + i)).block();
        }
        ListenerOptions options = ListenerOptions.DEFAULTS.withPollWait(Duration.ofMillis(200))
                .withCommitInterval(Duration.ofMillis(100)).withShutdownTimeout(Duration.ofSeconds(10));
        Map<String, List<Integer>> perKey = new ConcurrentHashMap<>();

        // First member: handles 30, then leaves the group.
        ReactiveStreamConsumer firstMember = consumer(cluster, topic);
        Long first = firstMember.process(options, record -> Mono.fromCallable(() -> {
                    perKey.computeIfAbsent(record.key().orElseThrow(), k -> new ArrayList<>()).add(entry(record.valueAsString()));
                    return record.offset();
                }))
                .take(30).count().block(Duration.ofSeconds(60));
        assertThat(first).isEqualTo(30);
        firstMember.close().block(Duration.ofSeconds(30));

        // A new member of the group picks up from the commits: everything left, each key still in order.
        ReactiveStreamConsumer secondMember = consumer(cluster, topic);
        Long rest = secondMember.process(options, record -> Mono.fromCallable(() -> {
                    perKey.computeIfAbsent(record.key().orElseThrow(), k -> new ArrayList<>()).add(entry(record.valueAsString()));
                    return record.offset();
                }))
                .take(Duration.ofSeconds(20)).count().block(Duration.ofSeconds(60));
        secondMember.close().block(Duration.ofSeconds(30));

        List<Integer> all = perKey.values().stream().flatMap(List::stream).toList();
        assertThat(all).as("every entry handled, at least once").containsAll(java.util.stream.IntStream.range(0, 60).boxed().toList());
        assertThat(first + rest).as("only records not committed by the first member are read again").isLessThan(60 + 30);
        perKey.values().forEach(entries -> assertThat(entries.stream().distinct().toList()).isSorted());
    }

    private static ReactiveStreamConsumer consumer(StreamConfig cluster, String topic) {
        return rx.provider("aws").streamConsumer(StreamConsumerConfig.builder(cluster, topic, "billing")
                .startPosition(StartPosition.EARLIEST).build());
    }

    private static int entry(String value) {
        return Integer.parseInt(value.substring("entry-".length()));
    }
}
