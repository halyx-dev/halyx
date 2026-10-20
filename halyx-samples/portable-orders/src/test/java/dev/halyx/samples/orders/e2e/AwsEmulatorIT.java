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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Set;

/**
 * The suite on the {@code aws} profile with every feature, against Floci (S3, DynamoDB, SNS, SQS, EventBridge, SSM
 * Parameter Store and Secrets Manager), PostgreSQL, Valkey and Kafka. The resources are created as the infrastructure
 * would create them, with the sample's default names.
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

    private static InProcessService service;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        AwsResources.provision(endpoint, SIGNING_KEY);
        SharedContainers.createAuditTopic(KAFKA);

        Map<String, String> properties = new LinkedHashMap<>(SharedContainers.properties(POSTGRES, VALKEY, KAFKA));
        properties.put("AWS_REGION", "us-east-1");
        properties.put("halyx.providers.aws.endpoint", endpoint.toString());
        properties.put("halyx.providers.aws.credentials.type", "static");
        properties.put("halyx.providers.aws.credentials.id", "test");
        properties.put("halyx.providers.aws.credentials.secret", "test");
        properties.put("halyx.providers.aws.properties.aws.s3.path-style", "true");
        service = new InProcessService(List.of("aws", "events", "streams", "cache", "sql"), properties);
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
        }
    }

    @Override
    protected OrdersClient client() {
        return service.client();
    }

    @Override
    protected Expectations expected() {
        return new Expectations("aws", Set.of("events", "streams", "cache", "sql"),
                Integer.parseInt(AwsResources.MAX_LINES), AwsResources.CURRENCY, SIGNING_KEY,
                Duration.ofSeconds(60));
    }
}
