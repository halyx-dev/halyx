package org.kamranzafar.cirrus.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end through the memory provider: parallel parts must reassemble in order. */
class ParallelUploadTest {

    private final Cirrus cirrus = Cirrus.builder().register("local", ProviderConfig.of("memory")).build();
    private final BlobStore store = cirrus.provider("local").blobStore("bucket");

    @AfterEach
    void tearDown() {
        cirrus.close();
    }

    @Test
    void parallelPartsReassembleInOrderAndValidate() {
        byte[] content = new byte[7 * PutOptions.MIN_PART_SIZE + 12345];
        new Random(42).nextBytes(content);
        var options = PutOptions.builder().partSize(PutOptions.MIN_PART_SIZE).uploadConcurrency(8)
                .validateChecksum().build();

        store.write(BlobKey.of("big.bin"), out -> {
            // Odd-sized writes that straddle part boundaries.
            for (int off = 0; off < content.length; off += 1_000_003) {
                out.write(content, off, Math.min(1_000_003, content.length - off));
            }
        }, options);

        try (Blob blob = store.get(BlobKey.of("big.bin"))) {
            assertThat(blob.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void uploadConcurrencyIsBounded() {
        assertThat(PutOptions.builder().build().uploadConcurrency()).isEqualTo(PutOptions.DEFAULT_UPLOAD_CONCURRENCY);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PutOptions.builder().uploadConcurrency(0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PutOptions.builder().uploadConcurrency(33))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
