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
package dev.halyx.storage;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.memory.InMemoryBlobs;
import dev.halyx.memory.InMemoryKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What core checks and costs for server-side keys, on the memory provider. */
class ServerSideKeyTest {

    private Halyx halyx;
    private BlobStore store;
    private KeyStore keys;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        store = halyx.provider("test").blobStore("b");
        keys = halyx.provider("test").keyStore();
        InMemoryKeys created = keys.unwrap(InMemoryKeys.class);
        created.create("storage", KeyPurpose.ENCRYPTION);
        created.create("tenant", KeyPurpose.ENCRYPTION);
        created.create("signer", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    private String stored(String key) {
        return store.unwrap(InMemoryBlobs.class).serverSideKey(BlobKey.of(key)).orElse(null);
    }

    @Test
    void theKeysMetadataIsReadOnceForManyWrites() {
        BlobStore keyed = store.withServerSideKey(keys.key("storage"));
        long reads = keys.statistics().metadataReads();

        for (int i = 0; i < 5; i++) {
            keyed.put(BlobKey.of("k" + i), BlobContent.ofString("v"));
        }

        assertThat(keys.statistics().metadataReads() - reads).isEqualTo(1);
        assertThat(keys.statistics().wraps()).as("the service encrypts, not Halyx").isZero();
    }

    @Test
    void aPutsOwnKeyWinsOverTheViews() {
        BlobStore keyed = store.withServerSideKey(keys.key("storage"));

        keyed.put(BlobKey.of("view"), BlobContent.ofString("v"));
        keyed.put(BlobKey.of("own"), BlobContent.ofString("v"),
                PutOptions.builder().serverSideKey(keys.key("tenant")).build());

        assertThat(stored("view")).isEqualTo(keys.key("storage").metadata().nativeId());
        assertThat(stored("own")).isEqualTo(keys.key("tenant").metadata().nativeId());
    }

    @Test
    void creatingTheViewCallsNoKeyService() {
        long reads = keys.statistics().metadataReads();

        store.withServerSideKey(keys.key("storage"));

        assertThat(keys.statistics().metadataReads()).isEqualTo(reads);
    }

    @Test
    void aKeyThatCantEncryptIsRefusedOnWrite() {
        BlobStore keyed = store.withServerSideKey(keys.key("signer"));

        assertThatThrownBy(() -> keyed.put(BlobKey.of("x"), BlobContent.ofString("x")))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("can't encrypt");
        assertThat(store.exists(BlobKey.of("x"))).isFalse();
    }

    @Test
    void aPlainCopyDoesNotCarryTheSourcesKey() {
        store.withServerSideKey(keys.key("storage")).put(BlobKey.of("src"), BlobContent.ofString("v"));

        store.copy(BlobKey.of("src"), BlobKey.of("plain-copy"));
        store.withServerSideKey(keys.key("storage")).copy(BlobKey.of("src"), BlobKey.of("keyed-copy"));

        assertThat(stored("plain-copy")).as("as on S3: the bucket's default").isNull();
        assertThat(stored("keyed-copy")).isEqualTo(keys.key("storage").metadata().nativeId());
    }
}
