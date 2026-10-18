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
package dev.halyx.spring.stream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.spring.messaging.HalyxListener;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamProducer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot on a real managed Kafka cluster with the cloud's login (tier 4): the starter's {@code StreamProducer}
 * bean and {@code @HalyxListener}, and Spring for Apache Kafka's {@code KafkaTemplate} and {@code @KafkaListener}
 * logging in through {@code halyx.spring-kafka.cluster}. Shared by the Boot 3 and Boot 4 test modules. Runs only
 * with {@code -Dhalyx.real.spring.streams=true}, from the real-cloud scripts' tier 4, which pass the provider type
 * ({@code halyx.real.spring.provider}), its properties ({@code halyx.real.spring.provider-properties}, as
 * {@code key=value,...}) and the cluster ({@code halyx.real.spring.bootstrap}).
 *
 * <p>The topics are created with Kafka's admin client, logged in with {@code CloudContext.kafkaClientProperties},
 * and deleted at the end.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.streams", matches = "true")
class RealSpringStreamIT {

    private static final String PROVIDER = System.getProperty("halyx.real.spring.provider");
    private static final String BOOTSTRAP = System.getProperty("halyx.real.spring.bootstrap");
    private static final Map<String, String> PROVIDER_PROPERTIES = parse(System.getProperty(
            "halyx.real.spring.provider-properties", ""));
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    static final String HALYX_TOPIC = "spring-halyx-" + SUFFIX;
    static final String KAFKA_TOPIC = "spring-kafka-" + SUFFIX;
    private static final Duration WAIT = Duration.ofSeconds(120);

    private static Halyx halyx;
    private static Admin admin;

    private static Map<String, String> parse(String pairs) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : pairs.split(",")) {
            if (!pair.isBlank()) {
                String[] kv = pair.split("=", 2);
                parsed.put(kv[0].trim(), kv[1].trim());
            }
        }
        return parsed;
    }

    private static StreamConfig cluster() {
        return StreamConfig.builder(BOOTSTRAP).auth(StreamAuth.iam()).build();
    }

    @BeforeAll
    static void createTopics() throws Exception {
        ProviderConfig provider = ProviderConfig.of(PROVIDER);
        for (Map.Entry<String, String> property : PROVIDER_PROPERTIES.entrySet()) {
            provider = provider.property(property.getKey(), property.getValue());
        }
        halyx = Halyx.builder().register("cloud", provider).build();
        admin = Admin.create(new HashMap<>(halyx.provider("cloud").kafkaClientProperties(cluster())));
        for (String topic : List.of(HALYX_TOPIC, KAFKA_TOPIC)) {
            admin.createTopics(List.of(new NewTopic(topic, Optional.of(3), Optional.empty()))).all().get();
            awaitServed(topic);
        }
    }

    @AfterAll
    static void deleteTopics() throws Exception {
        try {
            if (admin != null) {
                admin.deleteTopics(List.of(HALYX_TOPIC, KAFKA_TOPIC)).all().get();
                admin.close(Duration.ofSeconds(30));
            }
        } finally {
            if (halyx != null) {
                halyx.close();
            }
        }
    }

    private static void awaitServed(String topic) throws InterruptedException {
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (int p = 0; p < 3; p++) {
            latest.put(new TopicPartition(topic, p), OffsetSpec.latest());
        }
        Instant deadline = Instant.now().plusSeconds(60);
        while (true) {
            try {
                admin.listOffsets(latest).all().get();
                return;
            } catch (ExecutionException notYetServed) {
                assertThat(Instant.now()).as(topic + " served").isBefore(deadline);
                Thread.sleep(500);
            }
        }
    }

    static class Handlers {
        final Set<String> halyx = ConcurrentHashMap.newKeySet();
        final Set<String> kafka = ConcurrentHashMap.newKeySet();

        @HalyxListener("orders")
        void onHalyxRecord(String value) {
            halyx.add(value);
        }

        @KafkaListener(topics = "${real.spring.kafka-topic}", groupId = "spring-kafka-real")
        void onKafkaRecord(String value) {
            kafka.add(value);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlersConfig {
        @Bean
        Handlers handlers() {
            return new Handlers();
        }
    }

    private ApplicationContextRunner runner() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + PROVIDER,
                "halyx.stream-clusters.events.bootstrap-servers=" + BOOTSTRAP,
                "halyx.stream-clusters.events.auth.type=iam",
                "halyx.stream-producers.orders-out.topic=" + HALYX_TOPIC,
                "halyx.stream-consumers.orders.topic=" + HALYX_TOPIC,
                "halyx.stream-consumers.orders.group=spring-halyx-real",
                "halyx.stream-consumers.orders.start-position=earliest",
                "halyx.spring-kafka.cluster=events",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "real.spring.kafka-topic=" + KAFKA_TOPIC));
        PROVIDER_PROPERTIES.forEach((k, v) -> properties.add("halyx.providers.cloud.properties." + k + "=" + v));
        return new ApplicationContextRunner()
                .withConfiguration(HalyxSpringKafkaIT.CONFIGURATIONS)
                .withUserConfiguration(HandlersConfig.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    private static void await(Set<String> received, int count) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (received.size() < count) {
            assertThat(Instant.now()).as("records received").isBefore(deadline);
            Thread.sleep(200);
        }
    }

    @Test
    void starterAndSpringKafkaSendAndReceiveWithTheCloudLogin() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Handlers handlers = context.getBean(Handlers.class);

            StreamProducer producer = context.getBean("orders-out", StreamProducer.class);
            producer.sendAll(List.of(OutgoingRecord.of("a", "h1"), OutgoingRecord.of("b", "h2"),
                    OutgoingRecord.of("c", "h3")));
            producer.async().send("d", "h4").get(60, TimeUnit.SECONDS);

            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
            for (String value : List.of("k1", "k2", "k3")) {
                template.send(KAFKA_TOPIC, value, value).get(60, TimeUnit.SECONDS);
            }

            await(handlers.halyx, 4);
            await(handlers.kafka, 3);
            assertThat(handlers.halyx).containsExactlyInAnyOrder("h1", "h2", "h3", "h4");
            assertThat(handlers.kafka).containsExactlyInAnyOrder("k1", "k2", "k3");
        });
    }
}
