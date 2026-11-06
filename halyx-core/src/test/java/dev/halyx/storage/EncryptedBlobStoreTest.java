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
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** What the encrypted view asks of the store underneath: the requests a read costs. */
class EncryptedBlobStoreTest {

    private final List<String> operations = new CopyOnWriteArrayList<>();
    private Halyx halyx;
    private BlobStore view;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("local", ProviderConfig.of("memory"))
                .interceptor(new Interceptor() {
                    @Override
                    public <T> T intercept(OperationContext context, Invocation<T> next) {
                        if (context.service().equals("storage")) {
                            operations.add(context.operation());
                        }
                        return next.proceed();
                    }
                })
                .build();
        KeyStore keys = halyx.provider("local").keyStore(KeyStoreConfig.builder()
                .segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
        keys.unwrap(InMemoryKeys.class).create("blobs", KeyPurpose.ENCRYPTION);
        view = halyx.provider("local").blobStore("files").encryptedWith(keys.key("blobs"));
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    @Test
    void aRangeOfASmallBlobTakesOneRead() {
        byte[] data = random(500);
        view.put(BlobKey.of("small.json"), BlobContent.ofBytes(data));
        operations.clear();

        assertThat(range("small.json", 100, 50)).isEqualTo(Arrays.copyOfRange(data, 100, 150));

        assertThat(operations).as("the header read already holds the segment").containsExactly("get");
    }

    @Test
    void aRangeOfALargeBlobReadsTheHeaderThenOnlyItsSegments() {
        byte[] data = random(10 * KeyStoreConfig.MIN_SEGMENT_SIZE);
        view.put(BlobKey.of("large.bin"), BlobContent.ofBytes(data));
        operations.clear();

        int offset = 7 * KeyStoreConfig.MIN_SEGMENT_SIZE + 3;
        assertThat(range("large.bin", offset, 10)).isEqualTo(Arrays.copyOfRange(data, offset, offset + 10));

        assertThat(operations).containsExactly("get", "get");
    }

    @Test
    void headIsFreeWhenTheSizeWasKnownAndOneReadOtherwise() {
        view.put(BlobKey.of("sized.txt"), BlobContent.ofString("known length"));
        view.write(BlobKey.of("streamed.txt"), out -> out.write("unknown length".getBytes()));
        operations.clear();

        assertThat(view.head(BlobKey.of("sized.txt")).orElseThrow().size()).isEqualTo(12);
        assertThat(operations).containsExactly("head");
        operations.clear();
        assertThat(view.head(BlobKey.of("streamed.txt")).orElseThrow().size()).isEqualTo(14);
        assertThat(operations).containsExactly("head", "get");
    }

    private byte[] range(String key, long offset, long length) {
        try (Blob blob = view.get(BlobKey.of(key), GetOptions.range(ByteRange.of(offset, length)))) {
            return blob.readAllBytes();
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }
}
