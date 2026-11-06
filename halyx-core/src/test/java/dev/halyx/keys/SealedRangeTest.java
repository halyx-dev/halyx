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
package dev.halyx.keys;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.memory.InMemoryKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Random access to sealed data through {@link SealedHeader} and the ranged {@code decryptingStream}. */
class SealedRangeTest {

    private static final int SEGMENT = 4096;
    private static final byte[] AAD = "orders/receipt.pdf".getBytes(StandardCharsets.UTF_8);

    private Halyx halyx;
    private KeyStore store;
    private CryptoKey key;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build();
        store = halyx.provider("local").keyStore(KeyStoreConfig.builder().segmentSize(SEGMENT).build());
        store.unwrap(InMemoryKeys.class).create("data", KeyPurpose.ENCRYPTION);
        key = store.key("data");
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    @Test
    void everyRangeOfEveryLengthOpensToThePlaintext() {
        for (int length : new int[] {0, 1, SEGMENT - 1, SEGMENT, SEGMENT + 1, 3 * SEGMENT, 3 * SEGMENT + 17}) {
            byte[] plaintext = random(length);
            byte[] sealed = seal(plaintext);
            SealedHeader header = SealedHeader.read(new ByteArrayInputStream(sealed));

            assertThat(header.plaintextLength(sealed.length)).as("length %d", length).isEqualTo(length);
            assertThat(read(header, sealed, 0, length)).as("whole, length %d", length).isEqualTo(plaintext);
            int[] points = {0, 1, SEGMENT - 1, SEGMENT, SEGMENT + 1, 2 * SEGMENT + 5, length - 1, length};
            for (int from : points) {
                for (int to : points) {
                    if (from < 0 || to > length || from > to) {
                        continue;
                    }
                    assertThat(read(header, sealed, from, to - from)).as("length %d, [%d, %d)", length, from, to)
                            .isEqualTo(Arrays.copyOfRange(plaintext, from, to));
                }
            }
        }
    }

    @Test
    void onlyTheSegmentsARangeTouchesAreRead() {
        byte[] plaintext = random(5 * SEGMENT);
        byte[] sealed = seal(plaintext);
        SealedHeader header = SealedHeader.read(new ByteArrayInputStream(sealed));
        int full = SEGMENT + 16;

        assertThat(header.segmentStart(2 * SEGMENT + 10)).isEqualTo(header.length() + 2L * full);
        assertThat(header.segmentEnd(2 * SEGMENT + 20, sealed.length)).isEqualTo(header.length() + 3L * full);
        assertThat(header.segmentEnd(5 * SEGMENT, sealed.length)).isEqualTo(sealed.length);
        assertThat(header.segmentSize()).isEqualTo(SEGMENT);
        assertThat(header.keys()).singleElement().satisfies(entry ->
                assertThat(entry.keyId()).isEqualTo(key.metadata().keyId()));
    }

    @Test
    void changedTruncatedOrExtendedDataIsRefused() {
        byte[] plaintext = random(3 * SEGMENT + 100);
        byte[] sealed = seal(plaintext);
        SealedHeader header = SealedHeader.read(new ByteArrayInputStream(sealed));
        int full = SEGMENT + 16;

        byte[] flipped = sealed.clone();
        flipped[header.length() + full + 3] ^= 1;
        assertThatThrownBy(() -> read(header, flipped, SEGMENT, 10)).isInstanceOf(DataIntegrityException.class);

        // Cut at a segment boundary: the new last segment wasn't sealed as last.
        byte[] cut = Arrays.copyOf(sealed, header.length() + 2 * full);
        assertThatThrownBy(() -> read(header, cut, 0, (int) header.plaintextLength(cut.length)))
                .isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> read(header, Arrays.copyOf(sealed, sealed.length - 1), SEGMENT * 3, 10))
                .isInstanceOf(DataIntegrityException.class);
        byte[] extended = Arrays.copyOf(sealed, sealed.length + full);
        assertThatThrownBy(() -> read(header, extended, 0, (int) header.plaintextLength(extended.length)))
                .isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> header.plaintextLength(header.length() + 5)).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void theAadIsBoundAndRangesStayInsideThePlaintext() {
        byte[] sealed = seal(random(100));
        SealedHeader header = SealedHeader.read(new ByteArrayInputStream(sealed));

        assertThatThrownBy(() -> readAll(store.decryptingStream(header, body(header, sealed, 0), sealed.length, 0, 100,
                "other".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> store.decryptingStream(header, body(header, sealed, 0), sealed.length, 50, 51, AAD))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> SealedHeader.read(new ByteArrayInputStream("plain text".getBytes())))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> SealedHeader.read(new ByteArrayInputStream(Arrays.copyOf(sealed, 30))))
                .isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void anEmptyPlaintextIsStillAuthenticated() {
        byte[] sealed = seal(new byte[0]);
        SealedHeader header = SealedHeader.read(new ByteArrayInputStream(sealed));
        byte[] flipped = sealed.clone();
        flipped[sealed.length - 1] ^= 1;

        assertThat(read(header, sealed, 0, 0)).isEmpty();
        assertThatThrownBy(() -> read(header, flipped, 0, 0)).isInstanceOf(DataIntegrityException.class);
    }

    // Helpers

    private byte[] seal(byte[] plaintext) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = key.encryptingStream(sink, AAD)) {
            out.write(plaintext);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sink.toByteArray();
    }

    /** Reads {@code [offset, offset + length)} the way a blob store would: only the segments it touches. */
    private byte[] read(SealedHeader header, byte[] sealed, int offset, int length) {
        long start = header.segmentStart(offset);
        long end = header.segmentEnd(offset + length, sealed.length);
        InputStream segments = new ByteArrayInputStream(sealed, (int) start, (int) Math.max(0, end - start));
        return readAll(store.decryptingStream(header, segments, sealed.length, offset, length, AAD));
    }

    private static InputStream body(SealedHeader header, byte[] sealed, int offset) {
        long start = header.segmentStart(offset);
        return new ByteArrayInputStream(sealed, (int) start, sealed.length - (int) start);
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }
}
