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
package dev.halyx.samples.orders.e2e;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * The services every cloud's emulated deployment uses alongside its cloud emulator: PostgreSQL for the ledger, Valkey
 * for idempotency keys, and Kafka for the audit stream. The managed services on real clouds speak the same protocols.
 */
final class SharedContainers {

    static final String DB_USER = "portable_orders";
    static final String DB_PASSWORD = "e2e-password";
    static final String AUDIT_TOPIC = "portable-orders-audit";

    private SharedContainers() {
    }

    static GenericContainer<?> postgres() {
        return new GenericContainer<>("postgres:17-alpine")
                .withEnv("POSTGRES_USER", DB_USER)
                .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
                .withEnv("POSTGRES_DB", "orders")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
    }

    static GenericContainer<?> valkey() {
        return new GenericContainer<>(System.getProperty("valkey.image", "valkey/valkey:9.1.2-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
    }

    static KafkaContainer kafka() {
        return new KafkaContainer(System.getProperty("kafka.image", "apache/kafka:3.9.1"));
    }

    /** The audit topic, with a few partitions as a real deployment would have. */
    static void createAuditTopic(KafkaContainer kafka) throws ExecutionException, InterruptedException {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(AUDIT_TOPIC, 3, (short) 1))).all().get();
        }
    }

    /** What the service needs to reach the three, as the environment variables a deployment would set. */
    static Map<String, String> properties(GenericContainer<?> postgres, GenericContainer<?> valkey, KafkaContainer kafka) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("ORDERS_DB_HOST", postgres.getHost());
        properties.put("ORDERS_DB_PORT", String.valueOf(postgres.getMappedPort(5432)));
        properties.put("ORDERS_DB_AUTH", "password");
        properties.put("ORDERS_DB_USER", DB_USER);
        properties.put("ORDERS_DB_PASSWORD", DB_PASSWORD);
        properties.put("ORDERS_CACHE_ENDPOINT", "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379));
        properties.put("ORDERS_CACHE_AUTH", "none");
        properties.put("ORDERS_KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
        properties.put("ORDERS_KAFKA_AUTH", "none");
        return properties;
    }
}
