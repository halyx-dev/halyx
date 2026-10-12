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
package org.kamranzafar.halyx.kafka;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.CloudContext;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.error.AccessDeniedException;
import org.kamranzafar.halyx.stream.OutgoingRecord;
import org.kamranzafar.halyx.stream.ReceivedRecord;
import org.kamranzafar.halyx.stream.StartPosition;
import org.kamranzafar.halyx.stream.StreamAuth;
import org.kamranzafar.halyx.stream.StreamConfig;
import org.kamranzafar.halyx.stream.StreamConsumer;
import org.kamranzafar.halyx.stream.StreamConsumerConfig;
import org.kamranzafar.halyx.stream.StreamProducer;
import org.kamranzafar.halyx.stream.StreamProducerConfig;
import org.kamranzafar.halyx.tck.StreamConformanceTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IAM login end to end: clients log in with SASL {@code OAUTHBEARER} using tokens from a {@link KafkaCredentialsSource},
 * as the cloud providers' token sources do. The broker accepts unsigned tokens, so the whole TCK runs over this
 * login, tokens are replaced before they expire, and two clusters never share a login.
 */
@Testcontainers(disabledWithoutDocker = true)
class OAuthBearerLoginIT {

    @Container
    static final KafkaContainer KAFKA = KafkaBroker.oauthBearer();

    static final AtomicInteger LOGINS = new AtomicInteger();
    static Halyx halyx;

    /** An unsigned token for {@code principal}, valid for {@code lifetime}. */
    static KafkaLogin unsignedToken(String principal, Duration lifetime) {
        Instant now = Instant.now();
        Instant expires = now.plus(lifetime);
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64.encodeToString(("{\"sub\":\"" + principal + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + expires.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return new KafkaLogin(header + "." + claims + ".", principal, now, expires);
    }

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .plugin(new KafkaTestProvider(() -> () -> {
                    LOGINS.incrementAndGet();
                    return unsignedToken("halyx-app", Duration.ofMinutes(10));
                }))
                .register("kafka", ProviderConfig.of("kafka-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    /** IAM login over the broker's plaintext SASL listener; the clouds' listeners use TLS. */
    static StreamConfig.Builder iamCluster() {
        return StreamConfig.builder(KAFKA.getBootstrapServers()).auth(StreamAuth.iam())
                .property("kafka.security.protocol", "SASL_PLAINTEXT");
    }

    @Nested
    class WithIamLogin extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("kafka");
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return iamCluster();
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            KafkaBroker.createTopic(KAFKA, topic, partitions, KafkaBroker.unsignedLogin());
        }
    }

    @Test
    void clientsLogInWithTheCredentialsSource() {
        int before = LOGINS.get();
        String topic = "login-" + UUID.randomUUID();
        KafkaBroker.createTopic(KAFKA, topic, 1, KafkaBroker.unsignedLogin());

        halyx.provider("kafka").streamProducer(StreamProducerConfig.of(iamCluster().build(), topic)).send("k", "v");

        assertThat(LOGINS.get()).isGreaterThan(before);
    }

    @Test
    void tokensAreReplacedBeforeTheyExpire() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        try (Halyx shortLived = Halyx.builder()
                .plugin(new KafkaTestProvider(() -> () -> {
                    issued.incrementAndGet();
                    return unsignedToken("short-lived", Duration.ofSeconds(15));
                }))
                .register("kafka", ProviderConfig.of("kafka-test"))
                .build()) {
            String topic = "refresh-" + UUID.randomUUID();
            KafkaBroker.createTopic(KAFKA, topic, 1, KafkaBroker.unsignedLogin());
            StreamConfig cluster = iamCluster()
                    .property("kafka.sasl.login.refresh.min.period.seconds", "1")
                    .property("kafka.sasl.login.refresh.buffer.seconds", "1")
                    .build();
            StreamProducer producer = shortLived.provider("kafka").streamProducer(StreamProducerConfig.of(cluster, topic));
            producer.send("k", "before");

            Instant deadline = Instant.now().plusSeconds(45);
            while (issued.get() < 2) {
                assertThat(Instant.now()).as("a replacement token was fetched").isBefore(deadline);
                Thread.sleep(250);
            }
            // Past the first token's expiry: the connection re-authenticated with the replacement.
            Thread.sleep(Duration.ofSeconds(16).toMillis());
            producer.send("k", "after");

            try (StreamConsumer consumer = shortLived.provider("kafka").streamConsumer(StreamConsumerConfig
                    .builder(cluster, topic, "refresh").startPosition(StartPosition.EARLIEST).build())) {
                List<ReceivedRecord> records = new ArrayList<>();
                Instant end = Instant.now().plusSeconds(30);
                while (records.size() < 2 && Instant.now().isBefore(end)) {
                    records.addAll(consumer.poll(Duration.ofMillis(500)));
                }
                assertThat(records).extracting(ReceivedRecord::valueAsString).containsExactly("before", "after");
            }
        }
    }

    @Test
    void eachCredentialsSourceKeepsItsOwnLogin() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        String topic = "separate-" + UUID.randomUUID();
        KafkaBroker.createTopic(KAFKA, topic, 1, KafkaBroker.unsignedLogin());
        StreamConfig cluster = iamCluster().build();
        try (Halyx one = Halyx.builder().plugin(new KafkaTestProvider(() -> () -> {
                    first.incrementAndGet();
                    return unsignedToken("one", Duration.ofMinutes(10));
                })).register("kafka", ProviderConfig.of("kafka-test")).build();
             Halyx two = Halyx.builder().plugin(new KafkaTestProvider(() -> () -> {
                    second.incrementAndGet();
                    return unsignedToken("two", Duration.ofMinutes(10));
                })).register("kafka", ProviderConfig.of("kafka-test")).build()) {
            one.provider("kafka").streamProducer(StreamProducerConfig.of(cluster, topic)).send(OutgoingRecord.of("1"));
            two.provider("kafka").streamProducer(StreamProducerConfig.of(cluster, topic)).send(OutgoingRecord.of("2"));
        }

        assertThat(first.get()).isPositive();
        assertThat(second.get()).isPositive();
    }

    @Test
    void aRejectedTokenIsAccessDenied() {
        String topic = "rejected-" + UUID.randomUUID();
        KafkaBroker.createTopic(KAFKA, topic, 1, KafkaBroker.unsignedLogin());
        try (Halyx expired = Halyx.builder()
                .plugin(new KafkaTestProvider(() -> () -> {
                    Instant past = Instant.now().minusSeconds(600);
                    KafkaLogin stale = unsignedToken("stale", Duration.ofSeconds(1));
                    return new KafkaLogin(stale.token().replaceAll("\\.[^.]*\\.$", "." + Base64.getUrlEncoder()
                            .withoutPadding().encodeToString(("{\"sub\":\"stale\",\"iat\":" + past.getEpochSecond()
                                    + ",\"exp\":" + past.plusSeconds(1).getEpochSecond() + "}")
                                    .getBytes(StandardCharsets.UTF_8)) + "."), "stale", Instant.now(),
                            Instant.now().plusSeconds(600));
                }))
                .register("kafka", ProviderConfig.of("kafka-test"))
                .build()) {
            StreamProducer producer = expired.provider("kafka").streamProducer(StreamProducerConfig.of(
                    iamCluster().property("kafka.max.block.ms", "10000").build(), topic));

            assertThatThrownBy(() -> producer.send("k", "v")).isInstanceOf(AccessDeniedException.class);
        }
    }
}
