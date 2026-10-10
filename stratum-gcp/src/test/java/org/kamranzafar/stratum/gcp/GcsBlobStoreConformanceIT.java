package org.kamranzafar.stratum.gcp;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
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

/** Runs the storage TCK against Cloud Storage emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class GcsBlobStoreConformanceIT extends BlobStoreConformanceTest {

    private static final int PORT = 4588;
    private static final String BUCKET = "stratum-tck";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT));

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        stratum = Stratum.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(endpoint)
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "floci-local"))
                .build();
        stratum.provider("gcp").blobStore(BUCKET).unwrap(Storage.class).create(BucketInfo.of(BUCKET));
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
        return Map.of(
                "copyPreservesHttpHeaders",
                "Floci-gcp 0.10.0 drops cacheControl on copy/rewrite (GCS preserves all metadata)",
                "updateMetadataReplacesHeadersAndMetadataButNotContent",
                "Floci-gcp 0.10.0 PATCH replaces the metadata map and stores null values instead of removing keys");
    }

    @Override
    protected BlobStore blobStore() {
        return stratum.provider("gcp").blobStore(BUCKET);
    }
}
