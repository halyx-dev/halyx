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

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import dev.halyx.storage.BlobStore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/** The messaging TCK on Pub/Sub emulated by Floci, with claim checks through Floci's GCS. */
@Testcontainers(disabledWithoutDocker = true)
class PubSubConformanceIT extends PubSubConformance {

    private static final int PORT = 4588;

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT));

    private static Emulator emulator;

    @BeforeAll
    static void start() {
        emulator = new Emulator(FLOCI.getHost(), FLOCI.getMappedPort(PORT));
        emulator.halyx.provider("gcp").blobStore("claims").unwrap(Storage.class).create(BucketInfo.of("claims"));
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
    protected Map<String, String> knownEnvironmentGaps() {
        String noRedelivery = "Floci-gcp 0.10.0 Pub/Sub never redelivers: neither after the ack deadline "
                + "nor after ModifyAckDeadline(0); covered by PubSubEmulatorConformanceIT";
        return Map.of(
                "unsettledMessageReturnsAfterItsLease", noRedelivery,
                "nackedMessageIsRedelivered", noRedelivery,
                "listenerAcksSuccessesAndRetriesFailures", noRedelivery,
                "asyncNackRedelivers", noRedelivery);
    }

    @Override
    protected BlobStore claimCheckStore() {
        return emulator.halyx.provider("gcp").blobStore("claims");
    }
}
