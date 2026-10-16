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
package dev.halyx.azure.eventhubs;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka client properties for an Event Hubs namespace, for Kafka's own clients and Spring for Apache Kafka. */
class EventHubsClientPropertiesTest {

    @Test
    void entraIdLoginWithMicrosoftsSettingsAndIdempotenceLeftOn() {
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build()) {
            Map<String, Object> properties = halyx.provider("azure").kafkaClientProperties(
                    StreamConfig.builder("orders.servicebus.windows.net:9093").auth(StreamAuth.iam())
                            .property("kafka.linger.ms", "5").build());

            assertThat(properties)
                    .containsEntry("bootstrap.servers", "orders.servicebus.windows.net:9093")
                    .containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "OAUTHBEARER")
                    .containsEntry("connections.max.idle.ms", "180000")
                    .containsEntry("max.request.size", "1046528")
                    .containsEntry("linger.ms", "5")
                    .doesNotContainKey("enable.idempotence");
            assertThat(properties.get(KafkaStreamClients.CREDENTIALS)).isInstanceOf(EventHubsEntraTokens.class);
        }
    }
}
