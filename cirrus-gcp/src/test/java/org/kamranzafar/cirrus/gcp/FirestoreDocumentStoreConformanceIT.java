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
        String increments = "Floci-gcp 0.10.0 doesn't apply Firestore increments reliably (lost when concurrent, "
                + "set rather than added inside transactions); covered by FirestoreEmulatorDocumentStoreIT";
        return Map.of("concurrentIncrementsAreNotLost", increments,
                "transactionAppliesEveryWriteTogether", increments,
                "failedConditionRollsBackTheWholeTransaction", increments);
    }

    @Override
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return cirrus.provider("gcp").documentStore(indexes(table, sorted).build());
    }
}
