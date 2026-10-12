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

import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.spi.CloudProvider;
import org.kamranzafar.halyx.spi.ProviderConnection;
import org.kamranzafar.halyx.stream.StreamCapability;
import org.kamranzafar.halyx.stream.StreamConsumerConfig;
import org.kamranzafar.halyx.stream.StreamProducerConfig;
import org.kamranzafar.halyx.stream.spi.ConsumerDriver;
import org.kamranzafar.halyx.stream.spi.ProducerDriver;

import java.util.Map;
import java.util.function.Supplier;

/** A minimal provider exposing the shared Kafka driver, as the cloud modules do. Apache Kafka supports everything. */
final class KafkaTestProvider implements CloudProvider {

    private static final Capabilities CAPABILITIES = Capabilities.of(StreamCapability.values());

    private final Supplier<KafkaCredentialsSource> iam;

    KafkaTestProvider(Supplier<KafkaCredentialsSource> iam) {
        this.iam = iam;
    }

    @Override
    public String id() {
        return "kafka-test";
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        KafkaStreamClients clients = new KafkaStreamClients(id());
        return new ProviderConnection() {
            @Override
            public ProducerDriver streamProducer(StreamProducerConfig producer) {
                return clients.producer(producer, KafkaStreamClients.credentials(producer.cluster().auth(), iam),
                        CAPABILITIES, Map.of());
            }

            @Override
            public ConsumerDriver streamConsumer(StreamConsumerConfig consumer) {
                return clients.consumer(consumer, KafkaStreamClients.credentials(consumer.cluster().auth(), iam),
                        CAPABILITIES, Map.of());
            }

            @Override
            public void close() {
                clients.close();
            }
        };
    }
}
