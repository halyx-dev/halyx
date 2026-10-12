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
package org.kamranzafar.halyx.spring.stream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.kamranzafar.halyx.spring.messaging.HalyxListener;
import org.kamranzafar.halyx.stream.OutgoingRecord;
import org.kamranzafar.halyx.stream.StreamProducer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stream beans and {@code @HalyxListener} on Apache Kafka through the AWS provider, with the Kafka clients the
 * Boot version manages (3.9 on Boot 3.5, 4.x on Boot 4).
 */
@Testcontainers(disabledWithoutDocker = true)
class HalyxStreamKafkaIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(System.getProperty("kafka.image", "apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("payments", 3, (short) 1))).all().get();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (int p = 0; p < 3; p++) {
                latest.put(new TopicPartition("payments", p), OffsetSpec.latest());
            }
            Instant deadline = Instant.now().plusSeconds(30);
            while (true) {
                try {
                    admin.listOffsets(latest).all().get();
                    return;
                } catch (ExecutionException notYetServed) {
                    assertThat(Instant.now()).as("partitions served").isBefore(deadline);
                    Thread.sleep(100);
                }
            }
        }
    }

    static class Payments {
        final Set<String> handled = ConcurrentHashMap.newKeySet();

        @HalyxListener("payments")
        void onPayment(String value) {
            handled.add(value);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PaymentsConfig {
        @Bean
        Payments paymentHandler() {
            return new Payments();
        }
    }

    @Test
    void listenerHandlesRecordsSentThroughTheProducerBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
                .withUserConfiguration(PaymentsConfig.class)
                .withPropertyValues(
                        "halyx.providers.aws.type=aws",
                        "halyx.providers.aws.region=us-east-1",
                        "halyx.providers.aws.credentials.type=static",
                        "halyx.providers.aws.credentials.id=test",
                        "halyx.providers.aws.credentials.secret=test",
                        "halyx.stream-clusters.events.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "halyx.stream-producers.payments-out.topic=payments",
                        "halyx.stream-consumers.payments.start-position=earliest",
                        "halyx.stream-consumers.payments.listener.poll-wait=PT0.5S")
                .run(context -> {
                    StreamProducer producer = context.getBean("payments-out", StreamProducer.class);
                    assertThat(producer.partitionCount()).isEqualTo(3);
                    producer.sendAll(List.of(OutgoingRecord.of("a", "p1"), OutgoingRecord.of("b", "p2"),
                            OutgoingRecord.of("c", "p3")));

                    Payments payments = context.getBean(Payments.class);
                    Instant deadline = Instant.now().plusSeconds(60);
                    while (payments.handled.size() < 3) {
                        assertThat(Instant.now()).as("records handled").isBefore(deadline);
                        Thread.sleep(100);
                    }
                    assertThat(payments.handled).containsExactlyInAnyOrder("p1", "p2", "p3");
                });
    }
}
