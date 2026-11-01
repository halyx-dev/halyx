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
package dev.halyx.azure;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosContainerProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.AsyncDocumentStore;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.TableConfig;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.tck.CompletingThreads;
import dev.halyx.tck.DocumentStoreConformanceTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.assertThat;
import dev.halyx.tck.DocumentRepositoryConformanceTest;
import dev.halyx.tck.ReactiveDocumentRepositoryConformanceTest;

/** The document store TCK on Cosmos DB (NoSQL API) emulated by Floci-az. */
@Testcontainers(disabledWithoutDocker = true)
class CosmosDocumentStoreConformanceIT extends DocumentStoreConformanceTest {

    private static final int TLS_PORT = 4578;
    /** The well-known Cosmos DB emulator key. */
    private static final String KEY = "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static final Path KEYSTORE = selfSignedKeystore();

    /** The Cosmos SDK only speaks HTTPS, so Floci-az (a Quarkus app) gets a TLS port with a throwaway certificate. */
    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withCopyFileToContainer(MountableFile.forHostPath(KEYSTORE), "/certs/cosmos.p12")
            .withEnv("QUARKUS_HTTP_SSL_PORT", String.valueOf(TLS_PORT))
            .withEnv("QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_FILE", "/certs/cosmos.p12")
            .withEnv("QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_PASSWORD", "changeit")
            .withEnv("QUARKUS_HTTP_INSECURE_REQUESTS", "enabled")
            .withExposedPorts(TLS_PORT)
            .waitingFor(Wait.forLogMessage(".*Listening on.*https.*", 1));

    private static Halyx halyx;
    private static String endpoint;

    private static Path selfSignedKeystore() {
        try {
            Path dir = Files.createTempDirectory("halyx-cosmos");
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

    @BeforeAll
    static void start() {
        // Only for the local emulator's throwaway certificate: the SDK's documented switch for emulators.
        System.setProperty("COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED", "true");
        endpoint = "https://localhost:" + FLOCI.getMappedPort(TLS_PORT) + "/";
        try (CosmosClient admin = new CosmosClientBuilder().endpoint(endpoint).key(KEY).gatewayMode()
                .endpointDiscoveryEnabled(false).buildClient()) {
            admin.createDatabaseIfNotExists("halyx");
            for (String name : new String[] {"items", "items-sorted"}) {
                admin.getDatabase("halyx").createContainerIfNotExists(
                        new CosmosContainerProperties(name, "/pk").setDefaultTimeToLiveInSeconds(-1));
            }
        }
        halyx = Halyx.builder()
                .register("azure", ProviderConfig.of("azure")
                        .property(AzureProperties.COSMOS_ENDPOINT, endpoint)
                        .property(AzureProperties.COSMOS_KEY, KEY)
                        .property(AzureProperties.COSMOS_DATABASE, "halyx"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    @Override
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return halyx.provider("azure").documentStore(indexes(table, sorted).build());
    }

    /** The repository kit on the same tables. */
    @Nested
    class Repositories extends DocumentRepositoryConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            return CosmosDocumentStoreConformanceIT.this.store(sorted);
        }
    }

    /** The reactive repository kit on the same tables. */
    @Nested
    class ReactiveRepositories extends ReactiveDocumentRepositoryConformanceTest {
        private final ReactiveHalyx reactive = ReactiveHalyx.withVirtualThreads(halyx);

        @AfterEach
        void closeReactive() {
            reactive.close();
        }

        @Override
        protected DocumentStore store(boolean sorted) {
            return CosmosDocumentStoreConformanceIT.this.store(sorted);
        }

        @Override
        protected ReactiveHalyx reactive() {
            return reactive;
        }
    }

    @Test
    void asyncCallsCompleteOnTheSdksNonBlockingClient() throws Exception {
        CompletingThreads threads = new CompletingThreads();
        AsyncDocumentStore async = store(true).async(threads.executor());
        Key key = Key.of("async-threads-" + UUID.randomUUID(), "a");

        threads.track(() -> async.put(key, Document.builder().put("n", 1).build())).get(30, TimeUnit.SECONDS);
        threads.track(() -> async.get(key)).get(30, TimeUnit.SECONDS);
        threads.track(() -> async.query(Query.partition(key.partition()))).get(30, TimeUnit.SECONDS);
        threads.track(() -> async.delete(key)).get(30, TimeUnit.SECONDS);

        threads.assertNonBlocking(4, Thread.currentThread());
    }

    /**
     * Floci-az doesn't check account keys, so this shows a supplied key in use and asked for again while the store is in
     * use; that Cosmos DB signs with the updated key is shown on a real account.
     */
    @Test
    void aSuppliedAccountKeyIsAskedForAgainWhileInUse() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        try (Halyx rotating = Halyx.builder().register("azure", ProviderConfig.of("azure")
                .property(AzureProperties.COSMOS_ENDPOINT, endpoint)
                .property(AzureProperties.COSMOS_KEY, () -> {
                    asked.incrementAndGet();
                    return KEY;
                })
                .property(AzureProperties.COSMOS_DATABASE, "halyx")
                .property(AzureProperties.KEY_REFRESH_INTERVAL, "PT0.2S")).build()) {
            DocumentStore items = rotating.provider("azure").documentStore(TableConfig.builder("items").build());
            Key item = Key.of("rotation-" + UUID.randomUUID());

            items.put(item, Document.builder().put("n", 1).build());
            eventually(() -> assertThat(asked).hasValueGreaterThan(2));
            assertThat(items.get(item)).isPresent();
        }
    }

    /** Runs {@code check} until it passes, for up to 30 seconds. */
    private static void eventually(Runnable check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(200);
            }
        }
    }
}
