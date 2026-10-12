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
package org.kamranzafar.halyx.gcp;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.config.Credentials;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.storage.BlobStore;
import org.kamranzafar.halyx.tck.BlobStoreConformanceTest;
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
    private static final String BUCKET = "halyx-tck";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT));

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        halyx = Halyx.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(endpoint)
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "floci-local"))
                .build();
        halyx.provider("gcp").blobStore(BUCKET).unwrap(Storage.class).create(BucketInfo.of(BUCKET));
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
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
        return halyx.provider("gcp").blobStore(BUCKET);
    }
}
