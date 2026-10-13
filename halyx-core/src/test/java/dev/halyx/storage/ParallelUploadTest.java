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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end through the memory provider: parallel parts must reassemble in order. */
class ParallelUploadTest {

    private final Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build();
    private final BlobStore store = halyx.provider("local").blobStore("bucket");

    @AfterEach
    void tearDown() {
        halyx.close();
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
