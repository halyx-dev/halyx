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

import com.azure.core.credential.AccessToken;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.storage.blob.BlobContainerClient;
import dev.halyx.Halyx;
import dev.halyx.azure.parameters.ForceHttpPolicy;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The suite on the {@code azure} profile against Floci-az (Blob Storage, Cosmos DB, Key Vault secrets and keys, and App
 * Configuration), the Service Bus emulator, PostgreSQL, Valkey and Kafka. Event Grid has no emulator, so the
 * {@code events} feature is off here; the real-cloud run covers it. It runs with {@code keys} off, then again with it
 * on, sealing with a Key Vault key.
 */
@Testcontainers(disabledWithoutDocker = true)
class AzureEmulatorIT extends PortableOrdersSuite {

    static final String SIGNING_KEY = "azure-emulator-key";
    private static final int PORT = 4577;
    private static final int TLS_PORT = 4578;
    /** The well-known development storage account, and the Cosmos DB emulator's key. */
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String COSMOS_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static final String SQL_PASSWORD = "Portable-Orders-2026!";
    private static final Network NETWORK = Network.newNetwork();

    /** The Cosmos SDK only speaks HTTPS, so Floci-az gets a TLS port with a throwaway certificate. */
    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withCopyFileToContainer(MountableFile.forHostPath(selfSignedKeystore()), "/certs/cosmos.p12")
            .withEnv("QUARKUS_HTTP_SSL_PORT", String.valueOf(TLS_PORT))
            .withEnv("QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_FILE", "/certs/cosmos.p12")
            .withEnv("QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_PASSWORD", "changeit")
            .withEnv("QUARKUS_HTTP_INSECURE_REQUESTS", "enabled")
            .withExposedPorts(PORT, TLS_PORT)
            .waitingFor(Wait.forLogMessage(".*Listening on.*https.*", 1));

    static final GenericContainer<?> SQL_EDGE = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("sqledge.image", "mcr.microsoft.com/azure-sql-edge:2.0.0")))
            .withNetwork(NETWORK).withNetworkAliases("sqledge")
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", SQL_PASSWORD)
            .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    /** Service Bus entities are declared up front: the order-placed topic with its fulfilment subscription. */
    static final GenericContainer<?> SERVICE_BUS = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("servicebus.emulator.image", "mcr.microsoft.com/azure-messaging/servicebus-emulator:2.0.1")))
            .withNetwork(NETWORK)
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("SQL_SERVER", "sqledge")
            .withEnv("MSSQL_SA_PASSWORD", SQL_PASSWORD)
            .withCopyToContainer(Transferable.of("""
                    {"UserConfig":{"Namespaces":[{"Name":"sbemulatorns","Queues":[],"Topics":[
                      {"Name":"portable-orders-placed","Properties":{"DefaultMessageTimeToLive":"PT1H",
                        "DuplicateDetectionHistoryTimeWindow":"PT1M","RequiresDuplicateDetection":false},
                       "Subscriptions":[{"Name":"fulfilment","Properties":{"DeadLetteringOnMessageExpiration":false,
                         "DefaultMessageTimeToLive":"PT1H","LockDuration":"PT30S","MaxDeliveryCount":10,
                         "ForwardDeadLetteredMessagesTo":"","ForwardTo":"","RequiresSession":false},"Rules":[]}]}]}],
                      "Logging":{"Type":"File"}}}
                    """), "/ServiceBus_Emulator/ConfigFiles/Config.json")
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Emulator Service is Successfully Up!.*", 1))
            .withStartupTimeout(Duration.ofMinutes(4));

    @Container
    static final GenericContainer<?> POSTGRES = SharedContainers.postgres();
    @Container
    static final GenericContainer<?> VALKEY = SharedContainers.valkey();
    @Container
    static final KafkaContainer KAFKA = SharedContainers.kafka();

    private static final List<String> PROFILES = List.of("azure", "streams", "cache", "sql");
    private static final Set<String> FEATURES = Set.of("streams", "cache", "sql");

    private static InProcessService service;
    private static Map<String, String> properties;

    @BeforeAll
    static void start() throws Exception {
        SQL_EDGE.start();
        SERVICE_BUS.dependsOn(SQL_EDGE).start();
        // Only for the emulator's throwaway certificate: the Cosmos SDK's documented switch for emulators.
        System.setProperty("COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED", "true");
        String blobEndpoint = "http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT) + "/" + ACCOUNT;
        String cosmosEndpoint = "https://localhost:" + FLOCI.getMappedPort(TLS_PORT) + "/";
        // https is what the Key Vault and App Configuration SDKs require; insecure-http downgrades it on the wire.
        String vaultUrl = "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT) + "/" + ACCOUNT + "-keyvault";
        String appConfig = "Endpoint=https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT) + "/" + ACCOUNT
                + "-appconfig;Id=" + ACCOUNT + ";Secret=cGxhY2Vob2xkZXI=";
        String serviceBus = "Endpoint=sb://" + SERVICE_BUS.getHost() + ":" + SERVICE_BUS.getMappedPort(5672)
                + ";SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
        provision(blobEndpoint, cosmosEndpoint, vaultUrl, appConfig);
        SharedContainers.createAuditTopic(KAFKA);

        properties = new LinkedHashMap<>(SharedContainers.properties(POSTGRES, VALKEY, KAFKA));
        properties.put("ORDERS_STORAGE_ACCOUNT", ACCOUNT);
        properties.put("ORDERS_COSMOS_ENDPOINT", cosmosEndpoint);
        properties.put("ORDERS_SERVICEBUS_NAMESPACE", "sbemulatorns");
        properties.put("ORDERS_KEYVAULT_URL", vaultUrl);
        properties.put("ORDERS_APPCONFIG_ENDPOINT", "unused-with-a-connection-string");
        properties.put("ORDERS_CACHE_USER", "unused-without-entra-id");
        properties.put("halyx.providers.azure.endpoint", blobEndpoint);
        properties.put("halyx.providers.azure.credentials.type", "anonymous");
        properties.put("halyx.providers.azure.properties.azure.cosmos.key", COSMOS_KEY);
        properties.put("halyx.providers.azure.properties.azure.servicebus.connection-string", serviceBus);
        properties.put("halyx.parameter-stores.settings.properties.azure.appconfig.connection-string", appConfig);
        properties.put("halyx.parameter-stores.settings.properties.azure.emulator.insecure-http", "true");
        properties.put("halyx.parameter-stores.secrets.properties.azure.emulator.insecure-http", "true");
        service = new InProcessService(PROFILES, properties);
    }

    @AfterAll
    static void stop() {
        stopFirst();
        SERVICE_BUS.stop();
        SQL_EDGE.stop();
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
        return Optional.of(new StoredData(service.bean(Halyx.class).provider("azure")));
    }

    private static Expectations expectations(Set<String> features) {
        return new Expectations("azure", features, 6, "USD", SIGNING_KEY, Set.of("bulk-discount"), Duration.ofSeconds(60));
    }

    private static void stopFirst() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Nested
    class WithKeys extends PortableOrdersSuite {

        private static InProcessService keyed;

        /** After the outer tests: the first service stops, so it doesn't take this one's messages and records. */
        @BeforeAll
        static void start() {
            stopFirst();
            Map<String, String> keys = new LinkedHashMap<>(properties);
            // Only with the profile: the setting alone would declare the key store, which has no vault without it.
            keys.put("halyx.key-stores.order-keys.properties.azure.emulator.insecure-http", "true");
            keyed = new InProcessService(Features.with(PROFILES, "keys"), keys);
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
            return Optional.of(new StoredData(keyed.bean(Halyx.class).provider("azure")));
        }
    }

    /**
     * The receipts container, the Cosmos DB database and container, the settings and signing key, and the
     * {@code keys} feature's Key Vault key: RSA, for wrapping data keys.
     */
    private static void provision(String blobEndpoint, String cosmosEndpoint, String vaultUrl, String appConfig) {
        try (CosmosClient cosmos = new CosmosClientBuilder().endpoint(cosmosEndpoint).key(COSMOS_KEY).gatewayMode()
                .endpointDiscoveryEnabled(false).buildClient()) {
            cosmos.createDatabaseIfNotExists("portable-orders");
            cosmos.getDatabase("portable-orders").createContainerIfNotExists(
                    new CosmosContainerProperties("portable-orders", "/pk").setDefaultTimeToLiveInSeconds(-1));
        }
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")
                .endpoint(java.net.URI.create(blobEndpoint)).credentials(Credentials.anonymous())).build()) {
            halyx.provider("azure").blobStore("portable-orders-receipts").unwrap(BlobContainerClient.class)
                    .createIfNotExists();
            var settings = halyx.provider("azure").parameterStore(ParameterStoreConfig.parameters()
                    .prefix("portable-orders")
                    .property("azure.appconfig.connection-string", appConfig)
                    .property("azure.emulator.insecure-http", "true")
                    .build());
            settings.put("orders/max-lines", "6");
            settings.put("orders/currency", "USD");
            settings.put("features/bulk-discount", "true");
            halyx.provider("azure").parameterStore(ParameterStoreConfig.secrets().prefix("portable-orders")
                            .location(vaultUrl).property("azure.emulator.insecure-http", "true").build())
                    .put("receipt-signing-key", SIGNING_KEY);
        }
        new KeyClientBuilder().vaultUrl(vaultUrl)
                .credential(request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1))))
                .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient()
                .createRsaKey(new CreateRsaKeyOptions("portable-orders-customer-data").setKeySize(2048)
                        .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
    }

    private static Path selfSignedKeystore() {
        try {
            Path dir = Files.createTempDirectory("portable-orders-cosmos");
            Path keystore = dir.resolve("cosmos.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "floci", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "30", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
            }
            keystore.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return keystore;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
