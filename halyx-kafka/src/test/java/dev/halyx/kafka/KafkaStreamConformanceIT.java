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
package dev.halyx.kafka;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.tck.CountingExecutor;
import dev.halyx.tck.StreamConformanceTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The stream TCK on Apache Kafka, through the shared Kafka driver. */
@Testcontainers(disabledWithoutDocker = true)
class KafkaStreamConformanceIT {

    @Container
    static final KafkaContainer KAFKA = KafkaBroker.plaintext();

    static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .plugin(new KafkaTestProvider(KafkaStreamClients.iamUnsupported("kafka-test")))
                .register("kafka", ProviderConfig.of("kafka-test"))
                .register("memory", ProviderConfig.of("memory"))
                .build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Test
    void asyncSendsNeedNoBlockingThread() throws Exception {
        String topic = "threads-" + UUID.randomUUID();
        KafkaBroker.createTopic(KAFKA, topic, 1);
        StreamProducer producer = halyx.provider("kafka").streamProducer(StreamProducerConfig.builder(
                StreamConfig.builder(KAFKA.getBootstrapServers()).build(), topic).build());
        producer.send("k", "warm-up"); // the producer exists and knows the topic's partitions
        CountingExecutor blocking = new CountingExecutor();

        producer.async(blocking).send("k", "v").get(30, TimeUnit.SECONDS);
        producer.async(blocking).sendAll(List.of(OutgoingRecord.of("k", "a"), OutgoingRecord.of("k", "b")))
                .get(30, TimeUnit.SECONDS);

        blocking.assertUnused();
    }

    @Nested
    class WithoutAuth extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("kafka");
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return StreamConfig.builder(KAFKA.getBootstrapServers());
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            KafkaBroker.createTopic(KAFKA, topic, partitions);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return halyx.provider("memory").blobStore("claims");
        }
    }
}
