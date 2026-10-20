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

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.pubsub.v1.Subscription;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The suite on the {@code gcp} profile against Floci-gcp (Cloud Storage, Firestore, Pub/Sub and Secret Manager),
 * PostgreSQL, Valkey and Kafka. Eventarc Advanced has no emulator, so the {@code events} feature is off here. Parameter
 * Manager has none either, so this run reads the settings store from Secret Manager ({@code kind: secrets}); on Google
 * Cloud it is Parameter Manager.
 */
@Testcontainers(disabledWithoutDocker = true)
class GcpEmulatorIT extends PortableOrdersSuite {

    static final String SIGNING_KEY = "gcp-emulator-key";
    private static final int PORT = 4588;
    private static final String PROJECT = "floci-local";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT))
            .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    static final GenericContainer<?> POSTGRES = SharedContainers.postgres();
    @Container
    static final GenericContainer<?> VALKEY = SharedContainers.valkey();
    @Container
    static final KafkaContainer KAFKA = SharedContainers.kafka();

    private static InProcessService service;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        provision(endpoint);
        SharedContainers.createAuditTopic(KAFKA);

        Map<String, String> properties = new LinkedHashMap<>(SharedContainers.properties(POSTGRES, VALKEY, KAFKA));
        properties.put("GOOGLE_CLOUD_PROJECT", PROJECT);
        properties.put("halyx.providers.gcp.endpoint", endpoint.toString());
        properties.put("halyx.providers.gcp.credentials.type", "anonymous");
        properties.put("halyx.parameter-stores.settings.kind", "secrets");
        service = new InProcessService(List.of("gcp", "streams", "cache", "sql"), properties);
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
        return new Expectations("gcp", Set.of("streams", "cache", "sql"), 5, "AUD", SIGNING_KEY,
                Duration.ofSeconds(60));
    }

    /** The bucket, the Pub/Sub topic and subscription, and the settings and signing key; Firestore needs nothing. */
    private static void provision(URI endpoint) throws IOException {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext()
                .build();
        var transport = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                     .setTransportChannelProvider(transport).setCredentialsProvider(NoCredentialsProvider.create()).build());
             SubscriptionAdminClient subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                     .setTransportChannelProvider(transport).setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            String topic = "projects/" + PROJECT + "/topics/portable-orders-placed";
            topics.createTopic(topic);
            subscriptions.createSubscription(Subscription.newBuilder()
                    .setName("projects/" + PROJECT + "/subscriptions/portable-orders-fulfilment").setTopic(topic)
                    .setAckDeadlineSeconds(30).build());
        } finally {
            channel.shutdownNow();
        }
        try (Halyx halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp").endpoint(endpoint)
                .credentials(Credentials.anonymous()).property("gcp.project-id", PROJECT)).build()) {
            halyx.provider("gcp").blobStore("portable-orders-receipts").unwrap(Storage.class)
                    .create(BucketInfo.of("portable-orders-receipts"));
            var secrets = halyx.provider("gcp").parameterStore(ParameterStoreConfig.secrets().prefix("portable-orders")
                    .build());
            secrets.put("orders/max-lines", "5");
            secrets.put("orders/currency", "AUD");
            secrets.put("receipt-signing-key", SIGNING_KEY);
        }
    }
}
