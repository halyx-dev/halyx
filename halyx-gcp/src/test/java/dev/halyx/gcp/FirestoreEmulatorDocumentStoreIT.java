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
package dev.halyx.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.tck.DocumentStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;

/** The document store TCK on Google's Firestore emulator (native mode). */
@Testcontainers(disabledWithoutDocker = true)
class FirestoreEmulatorDocumentStoreIT extends DocumentStoreConformanceTest {

    private static final int PORT = 8080;

    @Container
    static final GenericContainer<?> EMULATOR = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("firestore.emulator.image", "gcr.io/google.com/cloudsdktool/google-cloud-cli:588.0.0-emulators")))
            .withCommand("gcloud", "emulators", "firestore", "start", "--host-port=0.0.0.0:" + PORT)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forLogMessage(".*Dev App Server is now running.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://" + EMULATOR.getHost() + ":" + EMULATOR.getMappedPort(PORT)))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "halyx-local"))
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
        return halyx.provider("gcp").documentStore(indexes(table, sorted).build());
    }
}
