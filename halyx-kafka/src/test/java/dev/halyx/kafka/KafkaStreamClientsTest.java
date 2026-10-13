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

import org.junit.jupiter.api.Test;
import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamProducerConfig;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaStreamClientsTest {

    private static final KafkaCredentialsSource TOKENS = () -> new KafkaLogin("t", "p", Instant.now(), Instant.now());

    @Test
    void withoutAuthOnlyTheBootstrapServersAreSet() {
        Map<String, Object> properties = KafkaStreamClients.clientProperties(
                StreamConfig.builder("a:9092, b:9092").build(), null, Map.of());

        assertThat(properties).containsOnly(Map.entry("bootstrap.servers", "a:9092, b:9092"));
    }

    @Test
    void passwordLoginUsesTheMechanismOverTls() {
        Map<String, Object> properties = KafkaStreamClients.clientProperties(StreamConfig.builder("a:9096")
                .auth(StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", "s3\"cr\\et")).build(), null, Map.of());

        assertThat(properties).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required "
                        + "password=\"s3\\\"cr\\\\et\" username=\"app\";");
    }

    @Test
    void iamLoginCarriesTheCredentialsToTheHandler() {
        Map<String, Object> properties = KafkaStreamClients.clientProperties(StreamConfig.builder("a:9098")
                .auth(StreamAuth.iam()).build(), TOKENS, Map.of());

        assertThat(properties).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "OAUTHBEARER")
                .containsEntry("sasl.login.callback.handler.class", OAuthBearerLoginHandler.class.getName())
                .containsEntry(KafkaStreamClients.CREDENTIALS, TOKENS);
        assertThat(properties.get("sasl.jaas.config").toString())
                .startsWith("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required halyxLogin=\"");
    }

    @Test
    void everyIamClientGetsItsOwnLoginId() {
        StreamConfig cluster = StreamConfig.builder("a:9098").auth(StreamAuth.iam()).build();

        Object first = KafkaStreamClients.clientProperties(cluster, TOKENS, Map.of()).get("sasl.jaas.config");
        Object second = KafkaStreamClients.clientProperties(cluster, TOKENS, Map.of()).get("sasl.jaas.config");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void withoutIdempotenceOneRequestIsInFlightSoOrderHolds() {
        StreamConfig cluster = StreamConfig.builder("a:9093").build();
        Map<String, Object> properties = new HashMap<>(Map.of("enable.idempotence", "false"));

        KafkaStreamClients.keepOrderWithoutIdempotence(properties, StreamProducerConfig.of(cluster, "t"));

        assertThat(properties).containsEntry("max.in.flight.requests.per.connection", "1");
    }

    @Test
    void idempotentProducersAndExplicitChoicesKeepTheirInFlightRequests() {
        StreamConfig cluster = StreamConfig.builder("a:9093").build();
        Map<String, Object> idempotent = new HashMap<>();
        KafkaStreamClients.keepOrderWithoutIdempotence(idempotent, StreamProducerConfig.of(cluster, "t"));
        assertThat(idempotent).doesNotContainKey("max.in.flight.requests.per.connection");

        Map<String, Object> chosen = new HashMap<>(Map.of("enable.idempotence", "false",
                "max.in.flight.requests.per.connection", "5"));
        KafkaStreamClients.keepOrderWithoutIdempotence(chosen, StreamProducerConfig.builder(cluster, "t")
                .property("kafka.max.in.flight.requests.per.connection", "5").build());
        assertThat(chosen).containsEntry("max.in.flight.requests.per.connection", "5");
    }

    @Test
    void providerDefaultsGiveWayToHalyxAndKafkaPropertiesWin() {
        Map<String, Object> properties = KafkaStreamClients.clientProperties(StreamConfig.builder("a:9093")
                        .auth(StreamAuth.iam())
                        .property("kafka.security.protocol", "SASL_PLAINTEXT")
                        .property("kafka.linger.ms", "5")
                        .property("aws.msk.region", "eu-west-2")
                        .build(), TOKENS,
                Map.of("request.timeout.ms", "60000", "security.protocol", "PLAINTEXT", "linger.ms", "1"));

        assertThat(properties).containsEntry("request.timeout.ms", "60000")
                .containsEntry("security.protocol", "SASL_PLAINTEXT")
                .containsEntry("linger.ms", "5")
                .doesNotContainKey("aws.msk.region")
                .doesNotContainKey("msk.region");
    }
}
