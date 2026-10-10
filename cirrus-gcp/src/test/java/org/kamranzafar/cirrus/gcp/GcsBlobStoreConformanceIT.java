package org.kamranzafar.cirrus.gcp;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.tck.BlobStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Map;

/** Runs the storage TCK against Cloud Storage emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class GcsBlobStoreConformanceIT extends BlobStoreConformanceTest {

    private static final int PORT = 4588;
    private static final String BUCKET = "cirrus-tck";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT));

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        cirrus = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(endpoint)
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "floci-local"))
                .build();
        cirrus.provider("gcp").blobStore(BUCKET).unwrap(Storage.class).create(BucketInfo.of(BUCKET));
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    /** Verified by sending raw HTTP requests to the emulator; the driver code path is correct. */
    @Override
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of(
                "copyPreservesHttpHeaders",
                "Floci-gcp 0.10.0 drops cacheControl on copy/rewrite (GCS preserves all metadata)",
                "updateMetadataReplacesHeadersAndMetadataButNotContent",
                "Floci-gcp 0.10.0 PATCH replaces the metadata map and stores null values instead of removing keys");
    }

    @Override
    protected BlobStore blobStore() {
        return cirrus.provider("gcp").blobStore(BUCKET);
    }
}
