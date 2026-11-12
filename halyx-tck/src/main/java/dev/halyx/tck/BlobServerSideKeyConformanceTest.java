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
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.MetadataUpdate;
import dev.halyx.storage.PresignedUpload;
import dev.halyx.storage.PresignedUploads;
import dev.halyx.storage.PresignedUrl;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.StorageCapability;
import dev.halyx.storage.StorageTier;
import dev.halyx.storage.UploadedPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Server-side encryption with a key from the provider's key service ({@code SERVER_SIDE_KMS}): every call that writes
 * stored bytes through a {@code withServerSideKey} view, or with {@code PutOptions.serverSideKey}, leaves the blob
 * encrypted with that key, as the provider's own API reports it; reads and presigned downloads are unchanged.
 */
public abstract class BlobServerSideKeyConformanceTest {

    private static final String TEXT = "{\"email\":\"ana@example.com\"}";

    private String prefix;

    /** The store under test, without a key: over an existing bucket. */
    protected abstract BlobStore blobStore();

    /** An encryption key of the store's provider that its storage service can use. */
    protected abstract CryptoKey serverSideKey();

    /**
     * The key the provider reports the stored blob is encrypted with, read through its own API (S3
     * {@code SSEKMSKeyId}, GCS {@code kmsKeyName}, Azure {@code encryptionScope}); empty for the bucket's default.
     */
    protected abstract Optional<String> storedServerSideKey(BlobKey key);

    /** What {@link #storedServerSideKey} reports for {@link #serverSideKey()}: its native id unless overridden. */
    protected String expectedServerSideKey() {
        return serverSideKey().metadata().nativeId();
    }

    /**
     * Whether the environment reports the key a blob is stored with. Emulators that accept the key without recording
     * it return false: every write path still runs with the key and its data is checked, and the real cloud tier checks
     * the key.
     */
    protected boolean reportsServerSideKeys() {
        return true;
    }

    /** Test names to skip because the environment doesn't implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        assumeTrue(blobStore().capabilities().supports(StorageCapability.SERVER_SIDE_KMS), "no server-side keys");
        prefix = "tck-sse-" + UUID.randomUUID() + "/";
    }

    private BlobKey key(String name) {
        return BlobKey.of(prefix + name);
    }

    private BlobStore keyed() {
        return blobStore().withServerSideKey(serverSideKey());
    }

    private void assertKeyed(BlobKey key) {
        if (reportsServerSideKeys()) {
            assertThat(storedServerSideKey(key)).as("the key %s is stored with", key).contains(expectedServerSideKey());
        }
    }

    private void assertNotKeyed(BlobKey key) {
        if (reportsServerSideKeys()) {
            assertThat(storedServerSideKey(key)).as("the bucket's default").isNotEqualTo(Optional.of(expectedServerSideKey()));
        }
    }

    // Writes

    @Test
    void putsAndStreamedWritesAreEncryptedWithTheKey() {
        BlobKey put = key("put.json");
        BlobKey written = key("written.json");

        keyed().put(put, BlobContent.ofString(TEXT), PutOptions.builder().contentType("application/json").build());
        keyed().write(written, out -> out.write(TEXT.getBytes(StandardCharsets.UTF_8)));

        assertKeyed(put);
        assertKeyed(written);
        assertThat(blobStore().get(put).readUtf8()).as("reads need nothing").isEqualTo(TEXT);
        assertThat(blobStore().head(put).orElseThrow().contentType()).contains("application/json");
    }

    @Test
    void uploadsInPartsAreEncryptedWithTheKey() {
        BlobKey key = key("parts.bin");
        byte[] data = random(PutOptions.MIN_PART_SIZE + 1024);

        keyed().write(key, out -> out.write(data), PutOptions.builder().partSize(PutOptions.MIN_PART_SIZE).build());

        assertKeyed(key);
        assertThat(blobStore().getBytes(key)).isEqualTo(data);
    }

    @Test
    void aPutCanCarryItsOwnKey() {
        BlobKey keyed = key("keyed.txt");
        BlobKey plain = key("plain.txt");

        blobStore().put(keyed, BlobContent.ofString(TEXT), PutOptions.builder().serverSideKey(serverSideKey()).build());
        blobStore().put(plain, BlobContent.ofString(TEXT));

        assertKeyed(keyed);
        assertNotKeyed(plain);
    }

    @Test
    void theAsyncViewEncryptsToo() {
        BlobKey key = key("async.txt");

        keyed().async().put(key, BlobContent.ofString(TEXT)).join();

        assertKeyed(key);
    }

    // Copies and rewrites in place

    @Test
    void copiesThroughTheViewAreEncryptedWithTheKey() {
        BlobKey source = key("source.txt");
        BlobKey target = key("target.txt");
        blobStore().put(source, BlobContent.ofString(TEXT));

        keyed().copy(source, target);

        assertKeyed(target);
        assertThat(blobStore().getBytes(target)).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
    }

    @Test
    void metadataAndTierChangesKeepTheKey() {
        BlobKey key = key("changed.txt");
        keyed().put(key, BlobContent.ofString(TEXT));

        if (blobStore().capabilities().supports(StorageCapability.USER_METADATA)) {
            keyed().updateMetadata(key, MetadataUpdate.builder().metadata("owner", "ana").build());
            assertKeyed(key);
            blobStore().updateMetadata(key, MetadataUpdate.builder().metadata("owner", "bo").build());
            assertKeyed(key);
        }
        if (blobStore().capabilities().supports(StorageCapability.STORAGE_TIERS)) {
            keyed().setTier(key, StorageTier.COOL);
            assertKeyed(key);
            blobStore().setTier(key, StorageTier.HOT);
            assertKeyed(key);
        }
        assertThat(blobStore().getBytes(key)).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
    }

    // Presigned URLs

    @Test
    void presignedUploadsAreEncryptedWithTheKey() throws Exception {
        assumeTrue(blobStore().capabilities().supports(StorageCapability.PRESIGNED_URL), "no presigned URLs");
        BlobKey key = key("uploaded.txt");

        PresignedUrl url = keyed().presignUpload(key, Duration.ofMinutes(5));
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.ofString(TEXT));

        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertKeyed(key);
        assertThat(blobStore().getBytes(key)).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
    }

    @Test
    void presignedDownloadsStillWork() throws Exception {
        assumeTrue(blobStore().capabilities().supports(StorageCapability.PRESIGNED_URL), "no presigned URLs");
        BlobKey key = key("download.txt");
        keyed().put(key, BlobContent.ofString(TEXT));

        HttpResponse<String> response = PresignedRequests.send(keyed().presignDownload(key, Duration.ofMinutes(5)),
                HttpRequest.BodyPublishers.noBody());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(TEXT);
    }

    @Test
    void presignedMultipartUploadsAreEncryptedWithTheKey() throws Exception {
        assumeTrue(blobStore().capabilities().supports(StorageCapability.PRESIGNED_MULTIPART), "no presigned multipart");
        BlobKey key = key("multipart.bin");
        int partSize = PresignedUploads.MIN_PART_SIZE;
        byte[] content = random(partSize + 1234);
        BlobStore keyed = keyed();
        PresignedUpload upload = keyed.startPresignedUpload(key, PutOptions.defaults());

        List<UploadedPart> parts = new ArrayList<>();
        for (int part = 1; part <= 2; part++) {
            int from = (part - 1) * partSize;
            int length = Math.min(partSize, content.length - from);
            HttpResponse<String> response = PresignedRequests.send(keyed.presignUploadPart(upload, part,
                    Duration.ofMinutes(10)), HttpRequest.BodyPublishers.ofByteArray(content, from, length));
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
            parts.add(new UploadedPart(part, response.headers().firstValue("ETag").orElse(null)));
        }
        keyed.completePresignedUpload(upload, parts);

        assertKeyed(key);
        assertThat(blobStore().getBytes(key)).isEqualTo(content);
    }

    // With client-side encryption, and the checks

    @Test
    void clientSideEncryptionCanSitOnTop() {
        try (Halyx memory = Halyx.builder().register("tck-keys", ProviderConfig.of("memory")).build()) {
            KeyStore keys = memory.provider("tck-keys").keyStore();
            keys.unwrap(InMemoryKeys.class).create("sealing", KeyPurpose.ENCRYPTION);
            BlobKey inner = key("inner.txt");
            BlobKey outer = key("outer.txt");

            keyed().encryptedWith(keys.key("sealing")).put(inner, BlobContent.ofString(TEXT));
            blobStore().encryptedWith(keys.key("sealing")).withServerSideKey(serverSideKey())
                    .put(outer, BlobContent.ofString(TEXT));

            for (BlobKey key : List.of(inner, outer)) {
                assertKeyed(key);
                assertThat(new String(blobStore().getBytes(key), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("HLXS");
                assertThat(blobStore().encryptedWith(keys.key("sealing")).getBytes(key))
                        .asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
            }
        }
    }

    @Test
    void aKeyFromAnotherProviderIsRefused() {
        assumeTrue(!blobStore().provider().equals("memory"), "the memory provider's keys are its own");
        try (Halyx memory = Halyx.builder().register("tck-keys", ProviderConfig.of("memory")).build()) {
            KeyStore keys = memory.provider("tck-keys").keyStore();
            keys.unwrap(InMemoryKeys.class).create("elsewhere", KeyPurpose.ENCRYPTION);

            assertThatThrownBy(() -> blobStore().withServerSideKey(keys.key("elsewhere")))
                    .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("memory key");
            assertThatThrownBy(() -> blobStore().put(key("x"), BlobContent.ofString("x"),
                    PutOptions.builder().serverSideKey(keys.key("elsewhere")).build()))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }
}
