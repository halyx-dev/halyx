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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * The suite against the built image ({@code -Dorders.image=...}, set by the {@code image} Maven profile), configured
 * only through its environment, as a deployment configures it: the AWS profile and every feature, on Floci,
 * PostgreSQL, Valkey and Kafka in one Docker network.
 */
@EnabledIfSystemProperty(named = "orders.image", matches = ".+")
class ImageIT extends PortableOrdersSuite {

    static final String SIGNING_KEY = "image-test-key";
    private static final Network NETWORK = Network.newNetwork();

    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withNetwork(NETWORK).withNetworkAliases("floci")
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());
    static final GenericContainer<?> POSTGRES = SharedContainers.postgres().withNetwork(NETWORK).withNetworkAliases("postgres");
    static final GenericContainer<?> VALKEY = SharedContainers.valkey().withNetwork(NETWORK).withNetworkAliases("valkey");
    static final KafkaContainer KAFKA = SharedContainers.kafka().withNetwork(NETWORK).withListener("kafka:19092");

    private static GenericContainer<?> service;
    private static OrdersClient client;

    @BeforeAll
    static void start() throws Exception {
        FLOCI.start();
        POSTGRES.start();
        VALKEY.start();
        KAFKA.start();
        AwsResources.provision(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566)), SIGNING_KEY);
        SharedContainers.createAuditTopic(KAFKA);

        service = new GenericContainer<>(DockerImageName.parse(System.getProperty("orders.image")))
                .withNetwork(NETWORK)
                .withEnv("SPRING_PROFILES_ACTIVE", "aws,events,streams,cache,sql")
                .withEnv("AWS_REGION", "us-east-1")
                .withEnv("ORDERS_DB_HOST", "postgres")
                .withEnv("ORDERS_DB_AUTH", "password")
                .withEnv("ORDERS_DB_USER", SharedContainers.DB_USER)
                .withEnv("ORDERS_DB_PASSWORD", SharedContainers.DB_PASSWORD)
                .withEnv("ORDERS_CACHE_ENDPOINT", "redis://valkey:6379")
                .withEnv("ORDERS_CACHE_AUTH", "none")
                .withEnv("ORDERS_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("ORDERS_KAFKA_AUTH", "none")
                // What only an emulator needs: its endpoint and fixed keys, and path-style S3 addressing.
                .withEnv("SPRING_APPLICATION_JSON", """
                        {"halyx":{"providers":{"aws":{"endpoint":"http://floci:4566",
                          "credentials":{"type":"static","id":"test","secret":"test"},
                          "properties":{"aws.s3.path-style":"true"}}}}}""")
                .withExposedPorts(8080)
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("portable-orders")))
                .waitingFor(Wait.forHttp("/actuator/health").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2));
        service.start();
        client = new OrdersClient(URI.create("http://" + service.getHost() + ":" + service.getMappedPort(8080)));
    }

    @AfterAll
    static void stop() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {service, KAFKA, VALKEY, POSTGRES, FLOCI}) {
            if (container != null) {
                container.stop();
            }
        }
        NETWORK.close();
    }

    @Override
    protected OrdersClient client() {
        return client;
    }

    @Override
    protected Expectations expected() {
        return new Expectations("aws", Set.of("events", "streams", "cache", "sql"),
                Integer.parseInt(AwsResources.MAX_LINES), AwsResources.CURRENCY, SIGNING_KEY, Duration.ofSeconds(60));
    }
}
