package org.kamranzafar.stratum.azure;

import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.storage.BlobStore;
import org.kamranzafar.stratum.tck.BlobStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Map;

/** Runs the storage TCK against Azure Blob Storage emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class AzureBlobStoreConformanceIT extends BlobStoreConformanceTest {

    private static final int PORT = 4577;
    private static final String CONTAINER = "stratum-tck";
    // Well-known development storage account, also used by Azurite.
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort());

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT) + "/" + ACCOUNT);
        stratum = Stratum.builder()
                .register("azure", ProviderConfig.of("azure")
                        .endpoint(endpoint)
                        .credentials(Credentials.of(ACCOUNT, KEY)))
                .build();
        stratum.provider("azure").blobStore(CONTAINER).unwrap(BlobContainerClient.class).createIfNotExists();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
        }
    }

    /** Verified by sending raw HTTP requests to the emulator; the driver code path is correct. */
    @Override
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of("writeInPartsWithIfNotExistsRejectsExistingBlob", "Floci-az 0.14.0 ignores If-None-Match on Put Block List (returns 201; Azure returns 409)");
    }

    @Override
    protected BlobStore blobStore() {
        return stratum.provider("azure").blobStore(CONTAINER);
    }
}
