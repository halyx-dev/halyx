package org.kamranzafar.cirrus.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.cirrus.storage.spi.MultipartUploadSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks how core splits streamed writes into driver calls (sequentially, so the call order is fixed). */
class ChunkingOutputStreamTest {

    private static final int PART = PutOptions.MIN_PART_SIZE;

    private final RecordingDriver driver = new RecordingDriver();
    private final BlobStore store = new DefaultBlobStore("test", "bucket", driver, InterceptorChain.empty());
    private final PutOptions options = PutOptions.builder().partSize(PART).uploadConcurrency(1).build();

    @Test
    void contentWithinOnePartIsASinglePut() {
        store.write(BlobKey.of("k"), out -> out.write(new byte[PART - 1]), options);

        assertThat(driver.calls).containsExactly("put:" + (PART - 1));
    }

    @Test
    void largerContentIsUploadedInFullPartsPlusRemainder() {
        store.write(BlobKey.of("k"), out -> out.write(new byte[2 * PART + 10]), options);

        assertThat(driver.calls).containsExactly("start", "part1:" + PART, "part2:" + PART, "part3:10", "complete");
    }

    @Test
    void exactMultipleHasNoEmptyTrailingPart() {
        store.write(BlobKey.of("k"), out -> out.write(new byte[2 * PART]), options);

        assertThat(driver.calls).containsExactly("start", "part1:" + PART, "part2:" + PART, "complete");
    }

    @Test
    void failureAbortsTheUpload() {
        assertThatThrownBy(() -> store.write(BlobKey.of("k"), out -> {
            out.write(new byte[PART]);
            throw new IllegalStateException("boom");
        }, options)).hasMessage("boom");

        assertThat(driver.calls).containsExactly("start", "part1:" + PART, "abort");
    }

    @Test
    void unknownLengthStreamIsChunked() {
        store.put(BlobKey.of("k"), BlobContent.ofStream(new ByteArrayInputStream(new byte[PART + 1])), options);

        assertThat(driver.calls).containsExactly("start", "part1:" + PART, "part2:1", "complete");
    }

    @Test
    void writesAfterCloseAreRejected() {
        assertThatThrownBy(() -> store.write(BlobKey.of("k"), out -> {
            out.close();
            out.write(1);
        }, options)).hasRootCauseMessage("Blob stream is closed");
    }

    @Test
    void validatedUploadsComputeOnlyTheDriversAlgorithms() {
        store.write(BlobKey.of("k"), out -> out.write(new byte[PART + 1]),
                PutOptions.builder().partSize(PART).uploadConcurrency(1).validateChecksum().build());
        store.put(BlobKey.of("small"), BlobContent.ofBytes(new byte[10]), PutOptions.builder().validateChecksum().build());

        assertThat(driver.expected).hasSize(2).allSatisfy(checksums ->
                assertThat(checksums.values().keySet()).containsExactly(ChecksumAlgorithm.CRC32C));
    }

    @Test
    void validatedLargeFileIsReadOnceThroughTheParts(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("big"), new byte[PART + 5]);
        Path small = Files.write(dir.resolve("small"), new byte[5]);
        var validated = PutOptions.builder().partSize(PART).uploadConcurrency(1).validateChecksum().build();

        store.put(BlobKey.of("big"), BlobContent.ofFile(file), validated);
        store.put(BlobKey.of("small"), BlobContent.ofFile(small), validated);

        assertThat(driver.calls).containsExactly("start", "part1:" + PART, "part2:5", "complete", "put:5");
    }

    private static final class RecordingDriver extends AbstractBlobStoreDriver {

        final List<String> calls = new ArrayList<>();
        final List<Checksums> expected = new ArrayList<>();

        RecordingDriver() {
            super("test", "bucket", Capabilities.of(StorageCapability.CHECKSUM));
        }

        @Override
        public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
            if (!expected.isEmpty()) {
                this.expected.add(expected);
            }
            calls.add("put:" + content.length());
            return new PutResult(key, ETag.of("e"));
        }

        @Override
        public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
            calls.add("start");
            return new MultipartUploadSession() {
                @Override
                public void uploadPart(int partNumber, byte[] data, int length) {
                    calls.add("part" + partNumber + ":" + length);
                }

                @Override
                public PutResult complete(Checksums expected) {
                    if (!expected.isEmpty()) {
                        RecordingDriver.this.expected.add(expected);
                    }
                    calls.add("complete");
                    return new PutResult(key, ETag.of("e"));
                }

                @Override
                public void abort() {
                    calls.add("abort");
                }
            };
        }

        @Override
        public Blob get(BlobKey key, GetOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BlobMetadata> head(BlobKey key) {
            return Optional.empty();
        }

        @Override
        public void delete(BlobKey key, DeleteOptions options) {
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
