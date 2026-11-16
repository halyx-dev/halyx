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

import dev.halyx.Halyx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The suite on the {@code aws} profile with every feature, against Floci (S3, DynamoDB, SNS, SQS, EventBridge, SSM
 * Parameter Store, Secrets Manager and KMS), PostgreSQL, Valkey and Kafka. The resources are created as the
 * infrastructure would create them, with the sample's default names. It runs with {@code keys} off, then again with
 * it on, sealing with a KMS key.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsEmulatorIT extends PortableOrdersSuite {

    static final String SIGNING_KEY = "aws-emulator-key";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());
    @Container
    static final GenericContainer<?> POSTGRES = SharedContainers.postgres();
    @Container
    static final GenericContainer<?> VALKEY = SharedContainers.valkey();
    @Container
    static final KafkaContainer KAFKA = SharedContainers.kafka();

    private static final List<String> PROFILES = List.of("aws", "events", "streams", "cache", "sql");
    private static final Set<String> FEATURES = Set.of("events", "streams", "cache", "sql");

    private static InProcessService service;
    private static Map<String, String> properties;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        AwsResources.provision(endpoint, SIGNING_KEY);
        SharedContainers.createAuditTopic(KAFKA);

        properties = new LinkedHashMap<>(SharedContainers.properties(POSTGRES, VALKEY, KAFKA));
        properties.put("AWS_REGION", "us-east-1");
        properties.put("halyx.providers.aws.endpoint", endpoint.toString());
        properties.put("halyx.providers.aws.credentials.type", "static");
        properties.put("halyx.providers.aws.credentials.id", "test");
        properties.put("halyx.providers.aws.credentials.secret", "test");
        properties.put("halyx.providers.aws.properties.aws.s3.path-style", "true");
        service = new InProcessService(PROFILES, properties);
    }

    @AfterAll
    static void stop() {
        stopFirst();
    }

    private static void stopFirst() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Override
    protected OrdersClient client() {
        return service.client();
    }

    @Override
    protected Expectations expected() {
        return expectations(FEATURES);
    }

    @Override
    protected Optional<StoredData> stored() {
        return Optional.of(new StoredData(service.bean(Halyx.class).provider("aws")));
    }

    private static Expectations expectations(Set<String> features) {
        return new Expectations("aws", features, Integer.parseInt(AwsResources.MAX_LINES), AwsResources.CURRENCY,
                SIGNING_KEY, Set.of("bulk-discount"), Duration.ofSeconds(60));
    }

    @Nested
    class WithKeys extends PortableOrdersSuite {

        private static InProcessService keyed;

        /** After the outer tests: the first service stops, so it doesn't take this one's messages and records. */
        @BeforeAll
        static void start() {
            stopFirst();
            keyed = new InProcessService(Features.with(PROFILES, "keys"), properties);
        }

        @AfterAll
        static void stop() {
            if (keyed != null) {
                keyed.close();
            }
        }

        @Override
        protected OrdersClient client() {
            return keyed.client();
        }

        @Override
        protected Expectations expected() {
            return expectations(Features.with(FEATURES, "keys"));
        }

        @Override
        protected Optional<StoredData> stored() {
            return Optional.of(new StoredData(keyed.bean(Halyx.class).provider("aws")));
        }
    }
}
