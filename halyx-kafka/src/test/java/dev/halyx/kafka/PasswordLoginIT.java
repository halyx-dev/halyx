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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** SCRAM login with a password given as a supplier, asked for each time a connection logs in. */
@Testcontainers(disabledWithoutDocker = true)
class PasswordLoginIT {

    @Container
    static final KafkaContainer KAFKA = KafkaBroker.scram();

    @Test
    void aFixedPasswordLogsIn() throws Exception {
        KafkaBroker.scramPassword(KAFKA, "fixed", "fixed-password");
        StreamConfig cluster = StreamConfig.builder(KAFKA.getBootstrapServers())
                .auth(StreamAuth.password(SaslMechanism.SCRAM_SHA_256, "fixed", "fixed-password"))
                .property("kafka.security.protocol", "SASL_PLAINTEXT").build();
        String topic = "fixed-" + UUID.randomUUID();
        Map<String, Object> admin = KafkaStreamClients.clientProperties(cluster, null, Map.of());
        admin.remove("bootstrap.servers");
        KafkaBroker.createTopic(KAFKA, topic, 1, admin);

        try (Halyx halyx = Halyx.builder()
                .plugin(new KafkaTestProvider(KafkaStreamClients.iamUnsupported("kafka-test")))
                .register("kafka", ProviderConfig.of("kafka-test"))
                .build()) {
            halyx.provider("kafka").streamProducer(StreamProducerConfig.of(cluster, topic)).send("k", "fixed");

            assertThat(poll(halyx, cluster, topic, "fixed", 1)).containsExactly("fixed");
        }
    }

    @Test
    void aRotatedPasswordReachesOpenConnectionsWhenTheyLogInAgain() throws Exception {
        KafkaBroker.scramPassword(KAFKA, "app", "password-1");
        AtomicReference<String> password = new AtomicReference<>("password-1");
        StreamConfig cluster = StreamConfig.builder(KAFKA.getBootstrapServers())
                .auth(StreamAuth.password(SaslMechanism.SCRAM_SHA_256, "app", password::get))
                .property("kafka.security.protocol", "SASL_PLAINTEXT").build();
        String topic = "rotation-" + UUID.randomUUID();
        Map<String, Object> admin = KafkaStreamClients.clientProperties(cluster, null, Map.of());
        admin.remove("bootstrap.servers");
        KafkaBroker.createTopic(KAFKA, topic, 1, admin);

        try (Halyx halyx = Halyx.builder()
                .plugin(new KafkaTestProvider(KafkaStreamClients.iamUnsupported("kafka-test")))
                .register("kafka", ProviderConfig.of("kafka-test"))
                .build()) {
            StreamProducer producer = halyx.provider("kafka").streamProducer(StreamProducerConfig.of(cluster, topic));
            producer.send("k", "before");

            // The old password stops working; the open connection logs in again within the broker's 3 seconds.
            KafkaBroker.scramPassword(KAFKA, "app", "password-2");
            password.set("password-2");
            Thread.sleep(Duration.ofSeconds(5).toMillis());
            producer.send("k", "after");

            assertThat(poll(halyx, cluster, topic, "rotation", 2)).containsExactly("before", "after");
        }
    }

    /** The values of the first {@code count} records on {@code topic}, read from the start. */
    private static List<String> poll(Halyx halyx, StreamConfig cluster, String topic, String group, int count) {
        try (StreamConsumer consumer = halyx.provider("kafka").streamConsumer(StreamConsumerConfig
                .builder(cluster, topic, group).startPosition(StartPosition.EARLIEST).build())) {
            List<String> values = new ArrayList<>();
            Instant end = Instant.now().plusSeconds(30);
            while (values.size() < count && Instant.now().isBefore(end)) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(record.valueAsString()));
            }
            return values;
        }
    }
}
