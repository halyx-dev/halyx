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

import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HlxsTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] DEK = seq(0x00, 32);
    private static final byte[] SALT = seq(0xa0, 16);
    private static final byte[] NONCE_PREFIX = seq(0xc0, 7);
    private static final byte[] AAD = "orders/o-1001".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAINTEXT =
            "{\"orderId\":\"o-1001\",\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);
    private static final List<WrappedKey> TWO_KEYS = List.of(
            new WrappedKey("aws-kms:arn:aws:kms:eu-west-2:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab", "",
                    WrapAlgorithm.AWS_KMS.id(), seq(0x50, 48)),
            new WrappedKey("azure-kv:https://acme.vault.azure.net/keys/orders", "3f2a9c41d0e14b6f8a7e5d2c1b0a9f88",
                    WrapAlgorithm.AZURE_RSA_OAEP_256.id(), seq(0x10, 32)));
    private static final List<WrappedKey> ONE_KEY = List.of(new WrappedKey("memory:/k", "1", 6, seq(0x70, 40)));

    /** The sample envelope in the #47 plan, from an independent generator: the format must never drift from it. */
    private static final String SAMPLE = "hlxs1:SExYUwEBAAAAIKChoqOkpaanqKmqq6ytrq_AwcLDxMXGGwJ2QUqGt7Wmf_iAB948J3n2O4rdYi"
            + "M-mfTeHNfJHLICAFNhd3Mta21zOmFybjphd3M6a21zOmV1LXdlc3QtMjoxMTExMjIyMjMzMzM6a2V5LzEyMzRhYmNkLTEyYWItMzRjZC01"
            + "NmVmLTEyMzQ1Njc4OTBhYgAAAQAwUFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_ADFhenVyZS1rdj"
            + "podHRwczovL2FjbWUudmF1bHQuYXp1cmUubmV0L2tleXMvb3JkZXJzACAzZjJhOWM0MWQwZTE0YjZmOGE3ZTVkMmMxYjBhOWY4OAIAIBAR"
            + "EhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4v6_ReU-zHObvs3IZZpxvjnvf11hk9jNZAEgWRhbTtnxidvjzbR_Uqgx2gSEIzhbnwIN"
            + "lS9kOd9f61qSRm9yesTZjdIAIEnYTsOm-PdwpW";

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        byte[] okm = Hlxs.hkdf(HEX.parseHex("0b".repeat(22)), HEX.parseHex("000102030405060708090a0b0c"),
                HEX.parseHex("f0f1f2f3f4f5f6f7f8f9"), 32);

        assertThat(HEX.formatHex(okm)).isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf");
    }

    @Test
    void sealsThePlansSampleEnvelopeByteForByte() {
        byte[] sealed = Hlxs.seal(DEK, TWO_KEYS, PLAINTEXT, AAD, 32, SALT, NONCE_PREFIX);

        assertThat(SealedData.of(sealed).toText()).isEqualTo(SAMPLE);
        assertThat(sealed).hasSize(402);
        assertThat(HEX.formatHex(Hlxs.commitment(DEK, SALT, 32)))
                .isEqualTo("1b0276414a86b7b5a67ff88007de3c2779f63b8add62233e99f4de1cd7c91cb2");
    }

    @Test
    void parsesAndOpensTheSample() {
        SealedData sample = SealedData.parseText(SAMPLE);

        assertThat(sample.keys()).extracting(WrappedKey::keyId).containsExactly(TWO_KEYS.get(0).keyId(),
                TWO_KEYS.get(1).keyId());
        assertThat(sample.keys().get(0).version()).isEmpty();
        assertThat(sample.keys().get(1).version()).contains("3f2a9c41d0e14b6f8a7e5d2c1b0a9f88");
        assertThat(sample.keys().get(1).algorithm()).contains(WrapAlgorithm.AZURE_RSA_OAEP_256);
        assertThat(sample.header().length()).isEqualTo(324);
        assertThat(Hlxs.open(DEK, sample.header(), sample.bytesUnsafe(), AAD)).isEqualTo(PLAINTEXT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 31, 32, 33, 64, 65, 1000})
    void roundTripsAnyLength(int length) {
        byte[] plaintext = random(length);
        byte[] sealed = Hlxs.seal(DEK, ONE_KEY, plaintext, AAD, 32, SALT, NONCE_PREFIX);
        Hlxs.Header header = Hlxs.parseHeader(sealed);

        assertThat(Hlxs.open(DEK, header, sealed, AAD)).isEqualTo(plaintext);
        assertThat((long) sealed.length).isEqualTo(Hlxs.sealedLength(length, header.length(), 32));
    }

    @Test
    void wrongAadFailsAuthentication() {
        byte[] sealed = sealedMultiSegment();

        assertThatThrownBy(() -> open(sealed, "orders/o-1002".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DataIntegrityException.class).hasMessageContaining("failed authentication");
        assertThatThrownBy(() -> open(sealed, new byte[0])).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void anotherDataKeyFailsTheCommitmentBeforeDecrypting() {
        byte[] sealed = sealedMultiSegment();

        assertThatThrownBy(() -> Hlxs.open(seq(0x40, 32), Hlxs.parseHeader(sealed), sealed, AAD))
                .isInstanceOf(DataIntegrityException.class).hasMessageContaining("key commitment");
    }

    @Test
    void everyFixedFieldIsChecked() {
        byte[] sealed = sealedMultiSegment();

        assertThatThrownBy(() -> open(flip(sealed, 0), AAD)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("Not HLXS");
        assertThatThrownBy(() -> open(flip(sealed, 4), AAD)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("format version");
        assertThatThrownBy(() -> open(flip(sealed, 5), AAD)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("algorithm");
        // Segment size, salt and commitment feed the commitment; the nonce prefix every segment's nonce.
        for (int offset : new int[] {9, 10, 25, 33, 64}) {
            assertThatThrownBy(() -> open(flip(sealed, offset), AAD)).as("offset %d", offset)
                    .isInstanceOf(DataIntegrityException.class);
        }
        assertThatThrownBy(() -> open(flip(sealed, 26), AAD)).isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("failed authentication");
    }

    @Test
    void everyBodyByteAndTagIsAuthenticated() {
        byte[] sealed = sealedMultiSegment();
        int body = Hlxs.parseHeader(sealed).length();

        for (int offset = body; offset < sealed.length; offset += 7) {
            int at = offset;
            assertThatThrownBy(() -> open(flip(sealed, at), AAD)).as("offset %d", at)
                    .isInstanceOf(DataIntegrityException.class);
        }
        assertThatThrownBy(() -> open(flip(sealed, sealed.length - 1), AAD)).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void truncatedReorderedOrExtendedDataFails() {
        byte[] sealed = sealedMultiSegment();
        int header = Hlxs.parseHeader(sealed).length();
        int full = 32 + Hlxs.TAG_LENGTH;

        assertThatThrownBy(() -> open(Arrays.copyOf(sealed, sealed.length - 1), AAD))
                .isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> open(Arrays.copyOf(sealed, header + full), AAD))
                .as("the final segment dropped").isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> open(Arrays.copyOf(sealed, header), AAD))
                .as("no segments").isInstanceOf(DataIntegrityException.class).hasMessageContaining("truncated");
        assertThatThrownBy(() -> open(Arrays.copyOf(sealed, 20), AAD))
                .as("a truncated header").isInstanceOf(DataIntegrityException.class).hasMessageContaining("header");

        byte[] swapped = sealed.clone();
        System.arraycopy(sealed, header + full, swapped, header, full);
        System.arraycopy(sealed, header, swapped, header + full, full);
        assertThatThrownBy(() -> open(swapped, AAD)).as("segments 0 and 1 swapped")
                .isInstanceOf(DataIntegrityException.class);

        byte[] extended = Arrays.copyOf(sealed, sealed.length + full);
        System.arraycopy(sealed, header, extended, sealed.length, full);
        assertThatThrownBy(() -> open(extended, AAD)).as("a segment appended")
                .isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void rewritingTheKeyListLeavesTheBodyUnchanged() {
        SealedData sealed = SealedData.of(sealedMultiSegment());
        List<WrappedKey> more = new ArrayList<>(sealed.keys());
        more.add(new WrappedKey("memory:/other", "2", 6, seq(0x01, 44)));

        SealedData rewritten = sealed.withKeys(more);

        int oldHeader = sealed.header().length();
        int newHeader = rewritten.header().length();
        assertThat(Arrays.copyOfRange(rewritten.bytesUnsafe(), newHeader, rewritten.bytesUnsafe().length))
                .isEqualTo(Arrays.copyOfRange(sealed.bytesUnsafe(), oldHeader, sealed.bytesUnsafe().length));
        assertThat(Hlxs.open(DEK, rewritten.header(), rewritten.bytesUnsafe(), AAD))
                .isEqualTo(Hlxs.open(DEK, sealed.header(), sealed.bytesUnsafe(), AAD));
        assertThat(rewritten.withoutKey("memory:/other")).isEqualTo(sealed);
    }

    @Test
    void keyListLimitsAreEnforced() {
        List<WrappedKey> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            sixteen.add(new WrappedKey("memory:/k" + i, "1", 6, seq(i, 40)));
        }
        byte[] sealed = Hlxs.seal(DEK, sixteen, PLAINTEXT, AAD, 32, SALT, NONCE_PREFIX);
        assertThat(SealedData.of(sealed).keys()).hasSize(16);

        List<WrappedKey> seventeen = new ArrayList<>(sixteen);
        seventeen.add(new WrappedKey("memory:/k16", "1", 6, seq(16, 40)));
        assertThatThrownBy(() -> Hlxs.seal(DEK, seventeen, PLAINTEXT, AAD, 32, SALT, NONCE_PREFIX))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("1 to 16 keys");
        assertThatThrownBy(() -> Hlxs.seal(DEK, List.of(ONE_KEY.get(0), ONE_KEY.get(0)), PLAINTEXT, AAD, 32, SALT,
                NONCE_PREFIX)).isInstanceOf(InvalidArgumentException.class).hasMessageContaining("more than once");
        SealedData one = SealedData.of(Hlxs.seal(DEK, ONE_KEY, PLAINTEXT, AAD, 32, SALT, NONCE_PREFIX));
        assertThatThrownBy(() -> one.withoutKey("memory:/k")).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("last key entry");
    }

    @Test
    void unknownWrapAlgorithmsAndSchemesStillParse() {
        List<WrappedKey> keys = List.of(new WrappedKey("x-future:key/1", "", 241, seq(0x05, 20)), ONE_KEY.get(0));
        SealedData sealed = SealedData.of(Hlxs.seal(DEK, keys, PLAINTEXT, AAD, 32, SALT, NONCE_PREFIX));

        assertThat(sealed.keys().get(0).algorithm()).isEmpty();
        assertThat(sealed.keys().get(0).scheme()).isEqualTo("x-future");
    }

    @Test
    void textAndBytesRoundTripAndToStringShowsNoBytes() {
        SealedData sealed = SealedData.of(sealedMultiSegment());

        assertThat(SealedData.parse(sealed.toBytes())).isEqualTo(sealed);
        assertThat(SealedData.parseText(sealed.toText())).isEqualTo(sealed);
        assertThat(sealed.toString()).isEqualTo("SealedData[WrappedKey[memory:/k, version 1]]");
        assertThatThrownBy(() -> SealedData.parseText("hlxs2:abc")).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> SealedData.parseText("hlxs1:%%%")).isInstanceOf(InvalidArgumentException.class);
    }

    private static byte[] sealedMultiSegment() {
        return Hlxs.seal(DEK, ONE_KEY, random(100), AAD, 32, SALT, NONCE_PREFIX);
    }

    private static byte[] open(byte[] sealed, byte[] aad) {
        return Hlxs.open(DEK, Hlxs.parseHeader(sealed), sealed, aad);
    }

    private static byte[] flip(byte[] bytes, int offset) {
        byte[] copy = bytes.clone();
        copy[offset] ^= 0x01;
        return copy;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }

    private static byte[] seq(int start, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }
}
