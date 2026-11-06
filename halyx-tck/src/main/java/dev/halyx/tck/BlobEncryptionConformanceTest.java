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
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.storage.Blob;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobMetadata;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.BlobSummary;
import dev.halyx.storage.ByteRange;
import dev.halyx.storage.GetOptions;
import dev.halyx.storage.ListOptions;
import dev.halyx.storage.MetadataUpdate;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.StorageCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What encrypting a blob store adds, beyond the blob kit run through the view: what is stored, tampering and the AAD,
 * wrong keys, ranges across segments, prefix scoping, a key per blob, key service calls, copies, and data written
 * with and without the key. Keys come from a memory key store with small segments, so emulators need no key service;
 * a subclass can use its provider's own keys with {@link #keys()}.
 */
public abstract class BlobEncryptionConformanceTest {

    private static final int SEGMENT = KeyStoreConfig.MIN_SEGMENT_SIZE;
    private static final String TEXT = "{\"email\":\"ana@example.com\"}";

    private Halyx memory;
    private KeyStore keys;
    private String prefix;

    /** The store under test, without encryption: over an existing bucket. */
    protected abstract BlobStore blobStore();

    /**
     * The key store the views seal with, holding encryption keys {@code blobs}, {@code tenant-a} and {@code tenant-b}.
     * A memory store with {@value #SEGMENT}-byte segments by default.
     */
    protected KeyStore keys() {
        if (keys == null) {
            memory = Halyx.builder().register("tck-keys", ProviderConfig.of("memory")).build();
            keys = memory.provider("tck-keys").keyStore(KeyStoreConfig.builder().location("tck-blobs")
                    .segmentSize(SEGMENT).build());
            InMemoryKeys created = keys.unwrap(InMemoryKeys.class);
            for (String name : new String[] {"blobs", "tenant-a", "tenant-b"}) {
                created.create(name, KeyPurpose.ENCRYPTION);
            }
        }
        return keys;
    }

    /** Test names to skip because the environment doesn't implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        prefix = "tck-enc-" + UUID.randomUUID() + "/";
    }

    @AfterEach
    void closeKeys() {
        if (memory != null) {
            memory.close();
            memory = null;
            keys = null;
        }
    }

    private BlobKey key(String name) {
        return BlobKey.of(prefix + name);
    }

    private CryptoKey blobs() {
        return keys().key("blobs");
    }

    private BlobStore sealed() {
        return blobStore().encryptedWith(blobs());
    }

    // What is stored

    @Test
    void theStoreHoldsSealedDataMarkedAsSuch() {
        BlobKey key = key("receipt.json");
        sealed().put(key, BlobContent.ofString(TEXT), PutOptions.builder().contentType("application/json").build());

        try (Blob raw = blobStore().get(key)) {
            byte[] stored = raw.readAllBytes();
            assertThat(new String(stored, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("HLXS");
            assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("ana@example.com");
            assertThat(raw.metadata().userMetadata()).containsEntry("halyx_encryption", "hlxs1");
            assertThat(raw.metadata().contentType()).contains("application/json");
        }
        assertThat(sealed().get(key).readUtf8()).isEqualTo(TEXT);
    }

    @Test
    void contentEncodingTravelsInTheMetadata() {
        assumeTrue(blobStore().capabilities().supports(StorageCapability.USER_METADATA), "no user metadata");
        BlobKey key = key("page.html.gz");
        sealed().put(key, BlobContent.ofString("<p>hi</p>"), PutOptions.builder().contentEncoding("gzip").build());

        assertThat(blobStore().head(key).orElseThrow().headers().contentEncoding())
                .as("nothing on the way may decompress the ciphertext").isEmpty();
        try (Blob blob = sealed().get(key)) {
            assertThat(blob.metadata().headers().contentEncoding()).contains("gzip");
            assertThat(blob.metadata().userMetadata()).doesNotContainKeys("halyx_encryption", "halyx_content_encoding");
            assertThat(blob.readUtf8()).isEqualTo("<p>hi</p>");
        }
    }

    @Test
    void sizesAreThePlaintextsExceptInListings() {
        BlobKey sized = key("sized.bin");
        BlobKey streamed = key("streamed.bin");
        byte[] data = random(3 * SEGMENT + 7);
        sealed().put(sized, BlobContent.ofBytes(data));
        sealed().write(streamed, out -> out.write(data));

        assertThat(sealed().head(sized).orElseThrow().size()).isEqualTo(data.length);
        assertThat(sealed().head(streamed).orElseThrow().size()).as("read from the header").isEqualTo(data.length);
        try (Blob blob = sealed().get(streamed)) {
            assertThat(blob.metadata().size()).isEqualTo(data.length);
        }
        long stored = blobStore().head(sized).orElseThrow().size();
        assertThat(sealed().list(ListOptions.prefix(prefix + "sized")).items()).extracting(BlobSummary::size)
                .as("D22: listings show the stored size").containsExactly(stored);
        assertThat(stored).isGreaterThan(data.length);
    }

    @Test
    void metadataNamesStartingWithHalyxAreReserved() {
        assertThatThrownBy(() -> sealed().put(key("x"), BlobContent.ofString("x"),
                PutOptions.builder().metadata("halyx_encryption", "none").build()))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void updatingMetadataKeepsTheBlobReadable() {
        assumeTrue(blobStore().capabilities().supports(StorageCapability.USER_METADATA), "no user metadata");
        BlobKey key = key("updated.txt");
        sealed().put(key, BlobContent.ofString(TEXT));

        BlobMetadata updated = sealed().updateMetadata(key, MetadataUpdate.builder().contentType("text/plain")
                .metadata("owner", "ana").build());

        assertThat(updated.size()).isEqualTo(TEXT.length());
        assertThat(updated.userMetadata()).containsExactly(Map.entry("owner", "ana"));
        assertThat(sealed().get(key).readUtf8()).isEqualTo(TEXT);
    }

    // Integrity

    @Test
    void aBlobMovedToAnotherKeyIsRefused() {
        BlobKey original = key("a.txt");
        sealed().put(original, BlobContent.ofString(TEXT));
        byte[] stored = blobStore().getBytes(original);
        Map<String, String> metadata = blobStore().head(original).orElseThrow().userMetadata();
        BlobKey moved = key("b.txt");
        blobStore().put(moved, BlobContent.ofBytes(stored), PutOptions.builder().metadata(metadata).build());

        assertThatThrownBy(() -> sealed().getBytes(moved)).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void changedOrTruncatedBlobsAreRefused() {
        BlobKey key = key("big.bin");
        byte[] data = random(4 * SEGMENT);
        sealed().put(key, BlobContent.ofBytes(data));
        byte[] stored = blobStore().getBytes(key);
        Map<String, String> metadata = blobStore().head(key).orElseThrow().userMetadata();

        byte[] flipped = stored.clone();
        flipped[stored.length - SEGMENT] ^= 1;
        blobStore().put(key, BlobContent.ofBytes(flipped), PutOptions.builder().metadata(metadata).build());
        assertThatThrownBy(() -> sealed().getBytes(key)).isInstanceOf(DataIntegrityException.class);
        assertThat(rangeOf(sealed(), key, 0, 10)).as("an untouched segment still opens")
                .isEqualTo(Arrays.copyOf(data, 10));

        blobStore().put(key, BlobContent.ofBytes(Arrays.copyOf(stored, stored.length - SEGMENT - 16)),
                PutOptions.builder().metadata(metadata).build());
        assertThatThrownBy(() -> sealed().getBytes(key)).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void aPlainBlobIsRefusedThroughTheView() {
        BlobKey key = key("plain.txt");
        blobStore().put(key, BlobContent.ofString(TEXT));

        assertThatThrownBy(() -> sealed().getBytes(key)).isInstanceOf(InvalidArgumentException.class);
        assertThat(sealed().head(key).orElseThrow().size()).as("reported as stored").isEqualTo(TEXT.length());
    }

    @Test
    void aSealedBlobReadWithoutTheKeyIsCiphertextNeverPlaintext() {
        BlobKey key = key("sealed.txt");
        sealed().put(key, BlobContent.ofString(TEXT));

        byte[] stored = blobStore().getBytes(key);

        assertThat(new String(stored, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("HLXS");
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("ana@");
    }

    @Test
    void aKeyFromAnotherStoreCantOpenIt() {
        BlobKey key = key("other.txt");
        sealed().put(key, BlobContent.ofString(TEXT));
        try (Halyx other = Halyx.builder().register("other", ProviderConfig.of("memory")).build()) {
            KeyStore elsewhere = other.provider("other").keyStore();
            elsewhere.unwrap(InMemoryKeys.class).create("blobs", KeyPurpose.ENCRYPTION);

            assertThatThrownBy(() -> blobStore().encryptedWith(elsewhere.key("blobs")).getBytes(key))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    // Ranges

    @Test
    void rangesAcrossSegmentsReadOnlyWhatTheyNeed() {
        assumeTrue(blobStore().capabilities().supports(StorageCapability.RANGE_READ), "no range reads");
        BlobKey key = key("ranges.bin");
        byte[] data = random(5 * SEGMENT + 123);
        sealed().put(key, BlobContent.ofBytes(data));

        for (int[] r : new int[][] {{0, 1}, {SEGMENT - 1, 2}, {SEGMENT, SEGMENT}, {2 * SEGMENT + 5, 2 * SEGMENT},
                {data.length - 1, 1}, {3, data.length - 3}}) {
            assertThat(rangeOf(sealed(), key, r[0], r[1])).as("[%d, +%d)", r[0], r[1])
                    .isEqualTo(Arrays.copyOfRange(data, r[0], r[0] + r[1]));
        }
        try (Blob blob = sealed().get(key, GetOptions.range(ByteRange.from(data.length - 10)))) {
            assertThat(blob.readAllBytes()).isEqualTo(Arrays.copyOfRange(data, data.length - 10, data.length));
        }
        try (Blob blob = sealed().get(key, GetOptions.builder().parallelism(4).partSize(1024 * 1024).build())) {
            assertThat(blob.readAllBytes()).as("parallel ranged reads of the stored bytes").isEqualTo(data);
        }
    }

    // Scoping and keys

    @Test
    void aPrefixViewSealsOnlyUnderItsPrefix() {
        BlobStore view = blobStore().encryptedWith(blobs(), prefix + "private/");
        BlobKey secret = key("private/a.txt");
        BlobKey open = key("public/a.txt");
        view.put(secret, BlobContent.ofString(TEXT));
        view.put(open, BlobContent.ofString(TEXT));

        assertThat(view.getBytes(secret)).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
        assertThat(blobStore().getBytes(open)).asString(StandardCharsets.UTF_8).as("plain outside").isEqualTo(TEXT);
        assertThat(new String(blobStore().getBytes(secret), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("HLXS");
        assertThat(view.list(ListOptions.prefix(prefix + "public/")).items()).extracting(BlobSummary::size)
                .containsExactly((long) TEXT.length());
        if (blobStore().capabilities().supports(StorageCapability.PRESIGNED_URL)) {
            assertThat(view.capabilities().supports(StorageCapability.PRESIGNED_URL)).isTrue();
            assertThat(view.presignDownload(open, Duration.ofMinutes(5)).uri()).isNotNull();
            assertThatThrownBy(() -> view.presignDownload(secret, Duration.ofMinutes(5)))
                    .isInstanceOf(UnsupportedFeatureException.class).hasMessageContaining("server-side");
        }
        assertThat(sealed().capabilities().supports(StorageCapability.PRESIGNED_URL)).isFalse();
        assertThat(sealed().capabilities().supports(StorageCapability.SERVER_SIDE_COPY)).isFalse();
    }

    @Test
    void aKeyPerBlobSealsEachWithItsOwnKey() {
        BlobStore tenants = blobStore().encryptedWith(keys(), blob -> blob.value().contains("/a/") ? "tenant-a"
                : "tenant-b");
        BlobKey a = key("a/doc.txt");
        BlobKey b = key("b/doc.txt");
        tenants.put(a, BlobContent.ofString("for a"));
        tenants.put(b, BlobContent.ofString("for b"));

        assertThat(tenants.getBytes(a)).asString(StandardCharsets.UTF_8).isEqualTo("for a");
        assertThat(tenants.getBytes(b)).asString(StandardCharsets.UTF_8).isEqualTo("for b");
        assertThat(header(a).keys()).singleElement().satisfies(entry ->
                assertThat(entry.keyId()).isEqualTo(keys().key("tenant-a").metadata().keyId()));
        assertThat(header(b).keys()).singleElement().satisfies(entry ->
                assertThat(entry.keyId()).isEqualTo(keys().key("tenant-b").metadata().keyId()));
    }

    @Test
    void eachWriteWrapsOnceAndEachReadUnwrapsOnce() {
        BlobKey key = key("counted.txt");
        CryptoKey blobs = blobs();
        blobs.metadata();
        long wraps = keys().statistics().wraps();
        long unwraps = keys().statistics().unwraps();

        sealed().put(key, BlobContent.ofString(TEXT));
        sealed().getBytes(key);
        rangeOf(sealed(), key, 1, 3);

        assertThat(keys().statistics().wraps() - wraps).isEqualTo(1);
        assertThat(keys().statistics().unwraps() - unwraps).isEqualTo(2);
    }

    // Copies and the asynchronous view

    @Test
    void copiesAreSealedForTheirNewKey() {
        BlobStore view = blobStore().encryptedWith(blobs(), prefix + "private/");
        BlobKey source = key("private/src.txt");
        view.put(source, BlobContent.ofString(TEXT), PutOptions.builder().contentType("text/plain").build());

        view.copy(source, key("private/dst.txt"));
        view.copy(source, key("public/out.txt"));
        blobStore().put(key("public/in.txt"), BlobContent.ofString("plain"));
        view.copy(key("public/in.txt"), key("private/in.txt"));

        assertThat(view.getBytes(key("private/dst.txt"))).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
        assertThat(view.head(key("private/dst.txt")).orElseThrow().contentType()).contains("text/plain");
        assertThat(blobStore().getBytes(key("public/out.txt"))).asString(StandardCharsets.UTF_8)
                .as("opened on the way out").isEqualTo(TEXT);
        assertThat(new String(blobStore().getBytes(key("private/in.txt")), 0, 4, StandardCharsets.US_ASCII))
                .as("sealed on the way in").isEqualTo("HLXS");
        assertThat(view.getBytes(key("private/in.txt"))).asString(StandardCharsets.UTF_8).isEqualTo("plain");
    }

    @Test
    void theAsyncViewSealsToo() {
        BlobKey key = key("async.txt");

        sealed().async().put(key, BlobContent.ofString(TEXT)).join();

        assertThat(new String(blobStore().getBytes(key), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("HLXS");
        assertThat(sealed().async().getBytes(key).join()).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
        assertThat(sealed().async().head(key).join().orElseThrow().size()).isEqualTo(TEXT.length());
    }

    // Helpers

    private dev.halyx.keys.SealedHeader header(BlobKey key) {
        return dev.halyx.keys.SealedHeader.read(new ByteArrayInputStream(blobStore().getBytes(key)));
    }

    private static byte[] rangeOf(BlobStore store, BlobKey key, long offset, long length) {
        try (Blob blob = store.get(key, GetOptions.range(ByteRange.of(offset, length)))) {
            return blob.readAllBytes();
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }
}
