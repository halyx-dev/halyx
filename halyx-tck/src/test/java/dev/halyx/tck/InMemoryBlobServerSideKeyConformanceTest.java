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
package dev.halyx.tck;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryBlobs;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.Optional;

/** The server-side key kit on the in-memory provider, which records the key each blob is stored with. */
class InMemoryBlobServerSideKeyConformanceTest extends BlobServerSideKeyConformanceTest {

    private static Halyx halyx;
    private static CryptoKey key;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        KeyStore keys = halyx.provider("test").keyStore();
        keys.unwrap(InMemoryKeys.class).create("storage", KeyPurpose.ENCRYPTION);
        key = keys.key("storage");
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Override
    protected BlobStore blobStore() {
        return halyx.provider("test").blobStore("tck");
    }

    @Override
    protected CryptoKey serverSideKey() {
        return key;
    }

    @Override
    protected Optional<String> storedServerSideKey(BlobKey blob) {
        return blobStore().unwrap(InMemoryBlobs.class).serverSideKey(blob);
    }
}
