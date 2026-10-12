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
package org.kamranzafar.halyx.storage;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.DataIntegrityException;
import org.kamranzafar.halyx.error.UnsupportedFeatureException;
import org.kamranzafar.halyx.interceptor.InterceptorChain;
import org.kamranzafar.halyx.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.halyx.storage.spi.ChecksumCalculator;
import org.kamranzafar.halyx.storage.spi.MultipartUploadSession;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integrity checks and batch fallbacks that no well-behaved provider or emulator can trigger. */
class IntegrityAndBatchTest {

    private static final byte[] DATA = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void uploadIsRejectedAndRemovedWhenProviderStoredDifferentBytes() {
        FakeDriver driver = new FakeDriver();
        driver.reportedChecksums = ChecksumCalculator.of(new byte[] {1, 2, 3}, 0, 3);
        BlobStore store = store(driver);

        assertThatThrownBy(() -> store.put(BlobKey.of("k"), BlobContent.ofBytes(DATA),
                PutOptions.builder().validateChecksum().build()))
                .isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("CRC32C");
        assertThat(driver.deletedConditionally).containsExactly("k@etag-1");
    }

    @Test
    void uploadSucceedsWhenChecksumsMatch() {
        FakeDriver driver = new FakeDriver();
        driver.reportedChecksums = ChecksumCalculator.of(DATA, 0, DATA.length);

        PutResult result = store(driver).put(BlobKey.of("k"), BlobContent.ofBytes(DATA),
                PutOptions.builder().validateChecksum().build());

        assertThat(result.etag()).isEqualTo(ETag.of("etag-1"));
        // Only the driver's algorithms (CRC32C by default) are computed.
        assertThat(driver.expectedSeen).isEqualTo(ChecksumCalculator.of(Set.of(ChecksumAlgorithm.CRC32C), DATA, 0, DATA.length));
    }

    @Test
    void uploadWithoutValidationPassesNoChecksums() {
        FakeDriver driver = new FakeDriver();
        store(driver).put(BlobKey.of("k"), BlobContent.ofBytes(DATA));

        assertThat(driver.expectedSeen.isEmpty()).isTrue();
    }

    @Test
    void corruptedDownloadFailsAtEndOfStream() {
        FakeDriver driver = new FakeDriver();
        driver.storedChecksums = ChecksumCalculator.of("something else".getBytes(StandardCharsets.UTF_8), 0, 14);
        BlobStore store = store(driver);

        try (Blob blob = store.get(BlobKey.of("k"), GetOptions.builder().validateChecksum().build())) {
            assertThatThrownBy(blob::readAllBytes).isInstanceOf(DataIntegrityException.class);
        }
    }

    @Test
    void intactDownloadValidates() {
        FakeDriver driver = new FakeDriver();
        driver.storedChecksums = ChecksumCalculator.of(DATA, 0, DATA.length);

        try (Blob blob = store(driver).get(BlobKey.of("k"), GetOptions.builder().validateChecksum().build())) {
            assertThat(blob.readAllBytes()).isEqualTo(DATA);
        }
    }

    @Test
    void downloadValidationWithoutStoredChecksumIsRejected() {
        assertThatThrownBy(() -> store(new FakeDriver()).get(BlobKey.of("k"),
                GetOptions.builder().validateChecksum().build()))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("no stored checksum");
    }

    @Test
    void rangeReadsCannotBeValidated() {
        assertThatThrownBy(() -> GetOptions.builder().range(ByteRange.of(0, 1)).validateChecksum().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void knownChecksumValues() {
        Checksums c = ChecksumCalculator.of("123456789".getBytes(StandardCharsets.US_ASCII), 0, 9);
        assertThat(c.crc32c()).hasValue("4waSgw==");
        assertThat(c.md5()).hasValue("JfnnlDI7RTiF9RgfG2JNCw==");
    }

    @Test
    void batchesAreSplitAtTheProviderLimit() {
        FakeDriver driver = new FakeDriver();
        driver.batchLimit = 3;
        List<BlobKey> keys = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            keys.add(BlobKey.of("k" + i));
        }

        BatchDeleteResult result = store(driver).deleteAll(keys);

        assertThat(driver.batchSizes).containsExactlyInAnyOrder(3, 3, 1);
        assertThat(result.deleted()).containsExactlyElementsOf(keys); // results keep key order
    }

    @Test
    void unimplementedBatchEndpointFallsBackToSingleDeletes() {
        FakeDriver driver = new FakeDriver();
        driver.batchLimit = 10;
        driver.batchUnsupported = true;

        BatchDeleteResult result = store(driver).deleteAll(List.of(BlobKey.of("a"), BlobKey.of("b")));

        assertThat(result.isComplete()).isTrue();
        assertThat(driver.singleDeletes).containsExactlyInAnyOrder("a", "b");
        assertThat(result.deleted()).containsExactly(BlobKey.of("a"), BlobKey.of("b"));
    }

    @Test
    void duplicateKeysAreDeletedOnce() {
        FakeDriver driver = new FakeDriver();
        driver.batchLimit = 10;

        BatchDeleteResult result = store(driver).deleteAll(List.of(BlobKey.of("a"), BlobKey.of("a")));

        assertThat(result.deleted()).containsExactly(BlobKey.of("a"));
    }

    private static BlobStore store(FakeDriver driver) {
        return new DefaultBlobStore("fake", "bucket", driver, InterceptorChain.empty());
    }

    private static final class FakeDriver extends AbstractBlobStoreDriver {

        Checksums reportedChecksums = Checksums.none();
        Checksums storedChecksums = Checksums.none();
        Checksums expectedSeen;
        int batchLimit;
        boolean batchUnsupported;
        final List<String> deletedConditionally = new ArrayList<>();
        // deleteAll sends batches and single deletes concurrently.
        final List<String> singleDeletes = new CopyOnWriteArrayList<>();
        final List<Integer> batchSizes = new CopyOnWriteArrayList<>();

        FakeDriver() {
            super("fake", "bucket", Capabilities.of(StorageCapability.values()));
        }

        @Override
        public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
            expectedSeen = expected;
            return new PutResult(key, ETag.of("etag-1"), reportedChecksums);
        }

        @Override
        public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Blob get(BlobKey key, GetOptions options) {
            BlobMetadata metadata = new BlobMetadata(key, DATA.length, ETag.of("etag-1"), Instant.now(),
                    BlobHeaders.none(), Map.of(), Optional.empty(), storedChecksums);
            return new Blob(metadata, new ByteArrayInputStream(DATA));
        }

        @Override
        public Optional<BlobMetadata> head(BlobKey key) {
            return Optional.empty();
        }

        @Override
        public void delete(BlobKey key, DeleteOptions options) {
            if (options.isConditional()) {
                deletedConditionally.add(key + "@" + options.ifMatchETag().orElseThrow().value());
            } else {
                singleDeletes.add(key.value());
            }
        }

        @Override
        public int maxBatchSize() {
            return batchLimit;
        }

        @Override
        public BatchDeleteResult deleteBatch(List<BlobKey> keys) {
            batchSizes.add(keys.size());
            if (batchUnsupported) {
                Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
                keys.forEach(k -> failed.put(k, new UnsupportedFeatureException("501")));
                return new BatchDeleteResult(List.of(), failed);
            }
            return new BatchDeleteResult(keys, Map.of());
        }

        @Override
        public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlobPage list(ListOptions options) {
            return new BlobPage(List.of(), Optional.empty());
        }

        @Override
        public PutResult copy(BlobKey source, BlobKey target) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Optional<CloudException> translateNative(Throwable error) {
            return Optional.empty();
        }

        @Override
        protected List<Object> nativeClients() {
            return List.of();
        }
    }
}
