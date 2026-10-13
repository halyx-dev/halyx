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
import dev.halyx.storage.BlobStore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * The whole messaging TCK on Google's own Pub/Sub emulator, which implements redelivery (ack deadlines and
 * ModifyAckDeadline) and ordering keys. Claim checks use an in-memory blob store, since it has no GCS.
 */
@Testcontainers(disabledWithoutDocker = true)
class PubSubEmulatorConformanceIT extends PubSubConformance {

    private static final int PORT = 8085;

    @Container
    static final GenericContainer<?> EMULATOR = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("pubsub.emulator.image", "gcr.io/google.com/cloudsdktool/google-cloud-cli:588.0.0-emulators")))
            .withCommand("gcloud", "beta", "emulators", "pubsub", "start", "--host-port=0.0.0.0:" + PORT,
                    "--project=" + PROJECT)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forLogMessage(".*Server started.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static Emulator emulator;

    @BeforeAll
    static void start() {
        emulator = new Emulator(EMULATOR.getHost(), EMULATOR.getMappedPort(PORT));
    }

    @AfterAll
    static void stop() {
        if (emulator != null) {
            emulator.close();
        }
    }

    @Override
    Emulator emulator() {
        return emulator;
    }

    @Override
    protected BlobStore claimCheckStore() {
        return emulator.halyx.provider("local").blobStore("claims");
    }
}
