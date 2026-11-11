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
package dev.halyx.memory;

import dev.halyx.storage.BlobKey;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;

/**
 * What the memory provider stores for one bucket, beyond what the portable API reports, so tests can check it. Reached
 * with {@code blobStore.unwrap(InMemoryBlobs.class)}:
 *
 * <pre>{@code
 * assertThat(store.unwrap(InMemoryBlobs.class).serverSideKey(key)).contains(cryptoKey.metadata().nativeId());
 * }</pre>
 */
public final class InMemoryBlobs {

    private final ConcurrentNavigableMap<String, InMemoryBlobStoreDriver.Entry> blobs;

    InMemoryBlobs(ConcurrentNavigableMap<String, InMemoryBlobStoreDriver.Entry> blobs) {
        this.blobs = blobs;
    }

    /**
     * The native id of the server-side key the blob is encrypted with at rest, as S3 reports {@code SSEKMSKeyId}; empty
     * for the bucket's default encryption, or when there is no such blob.
     */
    public Optional<String> serverSideKey(BlobKey key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(blobs.get(key.value())).map(InMemoryBlobStoreDriver.Entry::serverSideKey);
    }
}
