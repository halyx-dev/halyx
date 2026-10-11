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
package org.kamranzafar.cirrus.azure;

import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.tck.BlobStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the storage TCK against Azure Blob Storage emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class AzureBlobStoreConformanceIT extends BlobStoreConformanceTest {

    private static final int PORT = 4577;
    private static final String CONTAINER = "cirrus-tck";
    // Well-known development storage account, also used by Azurite.
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort());

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT) + "/" + ACCOUNT);
        cirrus = Cirrus.builder()
                .register("azure", ProviderConfig.of("azure")
                        .endpoint(endpoint)
                        .credentials(Credentials.of(ACCOUNT, KEY)))
                .build();
        cirrus.provider("azure").blobStore(CONTAINER).unwrap(BlobContainerClient.class).createIfNotExists();
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
        String noTier = "Floci-az 0.14.0 does not report x-ms-access-tier";
        String noSetProperties = "Floci-az 0.14.0 does not implement Set Blob Properties (501)";
        return Map.of(
                "writeInPartsWithIfNotExistsRejectsExistingBlob",
                "Floci-az 0.14.0 ignores If-None-Match on Put Block List (returns 201; Azure returns 409)",
                "setTierChangesTierButNotContent", "Floci-az 0.14.0 does not implement Set Blob Tier (501)",
                "setTierToCurrentTierIsANoOp", "Floci-az 0.14.0 does not implement Set Blob Tier (501)",
                "defaultTierIsHot", noTier,
                "putWithTierStoresTier", noTier,
                "updateMetadataReplacesHeadersAndMetadataButNotContent", noSetProperties,
                "updateMetadataKeepsTier", noSetProperties);
    }

    @Test
    void metadataNamesAzureCannotStoreAreRejectedClearly() {
        var options = PutOptions.builder().metadata("schema-version", "2").build();

        assertThatThrownBy(() -> blobStore().put(BlobKey.of("dashed.txt"), BlobContent.ofString("x"), options))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("schema-version");
        assertThat(blobStore().exists(BlobKey.of("dashed.txt"))).isFalse();
    }

    @Override
    protected BlobStore blobStore() {
        return cirrus.provider("azure").blobStore(CONTAINER);
    }
}
