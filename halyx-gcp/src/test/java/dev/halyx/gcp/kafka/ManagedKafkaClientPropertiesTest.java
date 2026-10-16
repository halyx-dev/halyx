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
package dev.halyx.gcp.kafka;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka client properties for a Managed Kafka cluster. IAM login needs Application Default Credentials, so it is
 * checked against the real service (RealGcpStreamIT); here, a password login and the cluster's own settings.
 */
class ManagedKafkaClientPropertiesTest {

    @Test
    void passwordLoginAndKafkaProperties() {
        try (Halyx halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp")).build()) {
            Map<String, Object> properties = halyx.provider("gcp").kafkaClientProperties(
                    StreamConfig.builder("bootstrap.events.europe-west2.managedkafka.p.cloud.goog:9092")
                            .auth(StreamAuth.password(SaslMechanism.PLAIN, "app@p.iam.gserviceaccount.com", "key"))
                            .property("kafka.client.id", "billing").build());

            assertThat(properties)
                    .containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "PLAIN")
                    .containsEntry("client.id", "billing")
                    .doesNotContainKey("halyx.kafka.credentials");
        }
    }
}
