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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.halyx.config.ProviderConfig;
import dev.halyx.kafka.KafkaCredentialsSource;
import dev.halyx.kafka.KafkaLogin;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.HalyxBuilderCustomizer;
import dev.halyx.spring.autoconfigure.HalyxSpringKafkaAutoConfiguration;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring for Apache Kafka ({@code KafkaTemplate} and {@code @KafkaListener}) on a Halyx stream cluster with IAM
 * login, on Boot 3.5 and Boot 4 with the Spring Kafka and Kafka clients each manages. The broker takes SASL
 * {@code OAUTHBEARER} and accepts unsigned tokens, which stand in for the clouds' IAM tokens; a test provider
 * hands them out through Halyx' login, and counts them.
 */
@Testcontainers(disabledWithoutDocker = true)
class HalyxSpringKafkaIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(System.getProperty("kafka.image", "apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
            .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_SASL_ENABLED_MECHANISMS", "OAUTHBEARER")
            .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_OAUTHBEARER_SASL_JAAS_CONFIG",
                    "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");

    static final AtomicInteger LOGINS = new AtomicInteger();

    /** Boot's Kafka auto-configuration: in spring-boot-autoconfigure on Boot 3, in spring-boot-kafka on Boot 4. */
    static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(HalyxAutoConfiguration.class,
            HalyxSpringKafkaAutoConfiguration.class, bootKafkaAutoConfiguration());

    static Class<?> bootKafkaAutoConfiguration() {
        for (String name : List.of("org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException notThisBoot) {
                // try the other
            }
        }
        throw new IllegalStateException("No Kafka auto-configuration on the classpath");
    }

    static KafkaLogin unsignedToken(String principal) {
        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofMinutes(10));
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64.encodeToString(("{\"sub\":\"" + principal + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + expires.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return new KafkaLogin(header + "." + claims + ".", principal, now, expires);
    }

    /** A provider whose IAM login hands out unsigned tokens, as the clouds' providers hand out theirs. */
    static final class TokenProvider implements CloudProvider {
        @Override
        public String id() {
            return "kafka-token";
        }

        @Override
        public ProviderConnection connect(ProviderConfig config) {
            KafkaCredentialsSource tokens = () -> {
                LOGINS.incrementAndGet();
                return unsignedToken("spring-app");
            };
            return new ProviderConnection() {
                @Override
                public Map<String, Object> kafkaClientProperties(StreamConfig cluster) {
                    return KafkaStreamClients.clientProperties(cluster,
                            KafkaStreamClients.credentials(cluster.auth(), () -> tokens), Map.of());
                }

                @Override
                public void close() {
                }
            };
        }
    }

    @BeforeAll
    static void createTopic() throws Exception {
        StreamConfig admin = StreamConfig.builder(KAFKA.getBootstrapServers()).auth(StreamAuth.iam())
                .property("kafka.security.protocol", "SASL_PLAINTEXT").build();
        Map<String, Object> settings = new HashMap<>(KafkaStreamClients.clientProperties(admin,
                () -> unsignedToken("admin"), Map.of()));
        try (Admin client = Admin.create(settings)) {
            client.createTopics(List.of(new NewTopic("orders", 3, (short) 1))).all().get();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (int p = 0; p < 3; p++) {
                latest.put(new TopicPartition("orders", p), OffsetSpec.latest());
            }
            Instant deadline = Instant.now().plusSeconds(30);
            while (true) {
                try {
                    client.listOffsets(latest).all().get();
                    return;
                } catch (ExecutionException notYetServed) {
                    assertThat(Instant.now()).as("partitions served").isBefore(deadline);
                    Thread.sleep(100);
                }
            }
        }
    }

    static class Orders {
        final Set<String> received = ConcurrentHashMap.newKeySet();

        @KafkaListener(topics = "orders", groupId = "spring-kafka-it")
        void onOrder(String value) {
            received.add(value);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrdersConfig {
        @Bean
        Orders orders() {
            return new Orders();
        }

        @Bean
        HalyxBuilderCustomizer tokenProvider() {
            return builder -> builder.plugin(new TokenProvider());
        }
    }

    /** No {@code spring.kafka.bootstrap-servers}: the Halyx cluster supplies the address as well as the login. */
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(CONFIGURATIONS)
                .withUserConfiguration(OrdersConfig.class)
                .withPropertyValues(
                        "halyx.providers.tokens.type=kafka-token",
                        "halyx.stream-clusters.events.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "halyx.stream-clusters.events.auth.type=iam",
                        "halyx.stream-clusters.events.properties.kafka.security.protocol=SASL_PLAINTEXT",
                        "spring.kafka.consumer.auto-offset-reset=earliest");
    }

    @Test
    void kafkaTemplateAndKafkaListenerLogInThroughHalyx() {
        // Logins happen as the listener starts with the context, and Kafka shares one per JAAS configuration.
        int before = LOGINS.get();
        runner().withPropertyValues("halyx.spring-kafka.cluster=events").run(context -> {
            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
            for (String order : List.of("o1", "o2", "o3")) {
                template.send("orders", order, order).get(30, TimeUnit.SECONDS);
            }

            Orders orders = context.getBean(Orders.class);
            Instant deadline = Instant.now().plusSeconds(60);
            while (orders.received.size() < 3) {
                assertThat(Instant.now()).as("records received").isBefore(deadline);
                Thread.sleep(100);
            }
            assertThat(orders.received).containsExactlyInAnyOrder("o1", "o2", "o3");
            assertThat(LOGINS.get()).as("tokens from Halyx' login").isGreaterThan(before);
        });
    }

    @Test
    void springKafkaIsLeftAloneWithoutTheProperty() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("halyxSpringKafkaLogin");
        });
    }

    @Test
    void anUnknownClusterIsReported() {
        runner().withPropertyValues("halyx.spring-kafka.cluster=other").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("halyx.spring-kafka.cluster refers to unknown stream cluster 'other'");
        });
    }
}
