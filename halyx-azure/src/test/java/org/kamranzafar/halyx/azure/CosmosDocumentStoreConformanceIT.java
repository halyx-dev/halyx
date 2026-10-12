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
package org.kamranzafar.halyx.azure;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosContainerProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.nosql.DocumentStore;
import org.kamranzafar.halyx.nosql.TableConfig;
import org.kamranzafar.halyx.tck.DocumentStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        String endpoint = "https://localhost:" + FLOCI.getMappedPort(TLS_PORT) + "/";
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
}
