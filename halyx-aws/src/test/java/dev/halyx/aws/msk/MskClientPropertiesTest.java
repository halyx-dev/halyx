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
package dev.halyx.aws.msk;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka client properties for an MSK cluster, for Kafka's own clients and Spring for Apache Kafka. */
class MskClientPropertiesTest {

    @Test
    void iamLoginWithTheConnectionsCredentials() {
        try (Halyx halyx = Halyx.builder().register("aws", ProviderConfig.of("aws").region("eu-west-2")).build()) {
            Map<String, Object> properties = halyx.provider("aws").kafkaClientProperties(
                    StreamConfig.builder("boot-abc.kafka-serverless.eu-west-2.amazonaws.com:9098")
                            .auth(StreamAuth.iam()).build());

            assertThat(properties)
                    .containsEntry("bootstrap.servers", "boot-abc.kafka-serverless.eu-west-2.amazonaws.com:9098")
                    .containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "OAUTHBEARER");
            assertThat(properties.get(KafkaStreamClients.CREDENTIALS)).isInstanceOf(MskIamTokens.class);
        }
    }

    @Test
    void withoutLoginOnlyTheClusterIsSet() {
        try (Halyx halyx = Halyx.builder().register("aws", ProviderConfig.of("aws").region("eu-west-2")).build()) {
            Map<String, Object> properties = halyx.provider("aws").kafkaClientProperties(
                    StreamConfig.builder("b-1.internal:9092").build());

            assertThat(properties).containsOnlyKeys("bootstrap.servers");
        }
    }
}
