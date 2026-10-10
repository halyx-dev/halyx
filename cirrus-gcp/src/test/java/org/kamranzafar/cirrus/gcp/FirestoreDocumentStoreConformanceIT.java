package org.kamranzafar.cirrus.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.tck.DocumentStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** The document store TCK on Firestore emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class FirestoreDocumentStoreConformanceIT extends DocumentStoreConformanceTest {

    private static final int PORT = 4588;

    @Container
    static final GenericContainer<?> EMULATOR = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://" + EMULATOR.getHost() + ":" + EMULATOR.getMappedPort(PORT)))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "cirrus-local"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    @Override
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of("concurrentIncrementsAreNotLost", "Floci-gcp 0.10.0 applies Firestore increments without "
                + "server-side atomicity (concurrent increments are lost); covered by FirestoreEmulatorDocumentStoreIT");
    }

    @Override
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return cirrus.provider("gcp").documentStore(table.build());
    }
}
