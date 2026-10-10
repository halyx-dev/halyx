package org.kamranzafar.cirrus.aws;

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
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.Map;

/** Runs the storage TCK against S3 emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class S3BlobStoreConformanceIT extends BlobStoreConformanceTest {

    private static final int PORT = 4566;
    private static final String BUCKET = "cirrus-tck";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort());

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        cirrus = Cirrus.builder()
                .register("aws", ProviderConfig.of("aws")
                        .region("us-east-1")
                        .endpoint(endpoint)
                        .credentials(Credentials.of("test", "test"))
                        .property(AwsProperties.S3_PATH_STYLE, "true"))
                .build();
        // Provisioning is outside the portable data-plane API: use the native client escape hatch.
        cirrus.provider("aws").blobStore(BUCKET).unwrap(S3Client.class).createBucket(b -> b.bucket(BUCKET));
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
        return Map.of("putStoresStandardHttpHeaders", "Floci 2.2.0 S3 drops the Content-Language header (Cache-Control and Content-Disposition are kept)");
    }

    @Override
    protected BlobStore blobStore() {
        return cirrus.provider("aws").blobStore(BUCKET);
    }
}
