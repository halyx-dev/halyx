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
package org.kamranzafar.halyx.azure;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.kamranzafar.halyx.CloudContext;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.config.Credentials;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.stream.StreamConfig;
import org.kamranzafar.halyx.tck.StreamConformanceTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * The stream TCK through the azure provider. No emulator has Event Hubs, so it runs against Apache Kafka without login,
 * with the provider's client defaults and capabilities; Entra ID tokens are unit-tested in {@code EventHubsEntraTokensTest}.
 */
@Testcontainers(disabledWithoutDocker = true)
class AzureStreamConformanceIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(System.getProperty("kafka.image", "apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure").credentials(Credentials.anonymous())).build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    /** Creates a topic and waits until the broker serves its partitions, as with an existing topic. */
    static void createTopic(String topic, int partitions) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            // Wait until the broker serves every partition: only then does ListOffsets answer.
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
    class OnApacheKafka extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("azure");
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return StreamConfig.builder(KAFKA.getBootstrapServers());
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            AzureStreamConformanceIT.createTopic(topic, partitions);
        }
    }
}
