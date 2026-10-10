package org.kamranzafar.stratum.tck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.stratum.capability.Capability;
import org.kamranzafar.stratum.error.NotFoundException;
import org.kamranzafar.stratum.error.PreconditionFailedException;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;
import org.kamranzafar.stratum.storage.BatchDeleteResult;
import org.kamranzafar.stratum.storage.Blob;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobHeaders;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobMetadata;
import org.kamranzafar.stratum.storage.BlobPage;
import org.kamranzafar.stratum.storage.BlobStore;
import org.kamranzafar.stratum.storage.BlobSummary;
import org.kamranzafar.stratum.storage.ByteRange;
import org.kamranzafar.stratum.storage.Checksums;
import org.kamranzafar.stratum.storage.DeleteOptions;
import org.kamranzafar.stratum.storage.GetOptions;
import org.kamranzafar.stratum.storage.ListOptions;
import org.kamranzafar.stratum.storage.MetadataUpdate;
import org.kamranzafar.stratum.storage.PresignRequest;
import org.kamranzafar.stratum.storage.PresignedUrl;
import org.kamranzafar.stratum.storage.PutOptions;
import org.kamranzafar.stratum.storage.PutResult;
import org.kamranzafar.stratum.storage.StorageCapability;
import org.kamranzafar.stratum.storage.StorageTier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for {@link BlobStore}. Every provider extends this class and supplies a store
 * over an existing, initially empty bucket. Tests for optional features are skipped when the provider
 * does not declare the matching {@link StorageCapability}.
 *
 * <p>Each test works under a fresh random key prefix, so tests never interfere with each other and
 * the bucket can be shared.
 */
public abstract class BlobStoreConformanceTest {

    private String prefix;

    /** The store under test, over an existing bucket. */
    protected abstract BlobStore blobStore();

    /**
     * Tests to skip because the environment (typically an emulator) does not implement the behaviour,
     * mapped to the reason. Keep this empty when running against a real cloud, and document each entry:
     * a skipped test is a gap in what has been verified.
     */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        prefix = "tck-" + UUID.randomUUID() + "/";
    }

    protected BlobKey key(String name) {
        return BlobKey.of(prefix + name);
    }

    protected void assumeCapability(Capability capability) {
        assumeTrue(blobStore().capabilities().supports(capability),
                () -> blobStore().provider() + " does not support " + capability.name());
    }

    private PutResult putText(BlobKey key, String text) {
        return blobStore().put(key, BlobContent.ofString(text));
    }

    private String readText(BlobKey key) {
        return new String(blobStore().getBytes(key), StandardCharsets.UTF_8);
    }

    // --- basic CRUD ----------------------------------------------------------------------------

    @Test
    void putThenGetRoundTripsContent() {
        BlobKey key = key("hello.txt");
        putText(key, "hello world");

        assertThat(readText(key)).isEqualTo("hello world");
    }

    @Test
    void putReturnsETagMatchingHead() {
        BlobKey key = key("etag.txt");
        PutResult result = putText(key, "content");

        assertThat(result.key()).isEqualTo(key);
        assertThat(blobStore().head(key)).get().extracting(BlobMetadata::etag).isEqualTo(result.etag());
    }

    @Test
    void putOverwritesExistingBlob() {
        BlobKey key = key("overwrite.txt");
        putText(key, "first");
        putText(key, "second");

        assertThat(readText(key)).isEqualTo("second");
    }

    @Test
    void putFromStream() {
        BlobKey key = key("stream.bin");
        byte[] data = "streamed content".getBytes(StandardCharsets.UTF_8);
        blobStore().put(key, BlobContent.ofStream(new ByteArrayInputStream(data), data.length));

        assertThat(blobStore().getBytes(key)).isEqualTo(data);
    }

    @Test
    void putFromFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("upload.txt"), "file content");
        BlobKey key = key("file.txt");
        blobStore().put(key, BlobContent.ofFile(file));

        assertThat(readText(key)).isEqualTo("file content");
    }

    @Test
    void emptyBlobRoundTrips() {
        BlobKey key = key("empty");
        blobStore().put(key, BlobContent.ofBytes(new byte[0]));

        assertThat(blobStore().getBytes(key)).isEmpty();
        assertThat(blobStore().head(key)).get().extracting(BlobMetadata::size).isEqualTo(0L);
    }

    @Test
    void getMissingBlobThrowsNotFound() {
        assertThatThrownBy(() -> blobStore().get(key("missing")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void headMissingBlobReturnsEmpty() {
        assertThat(blobStore().head(key("missing"))).isEmpty();
        assertThat(blobStore().exists(key("missing"))).isFalse();
    }

    @Test
    void headReturnsSizeAndLastModified() {
        BlobKey key = key("head.txt");
        putText(key, "12345");

        BlobMetadata metadata = blobStore().head(key).orElseThrow();
        assertThat(metadata.key()).isEqualTo(key);
        assertThat(metadata.size()).isEqualTo(5);
        assertThat(metadata.lastModified()).isNotNull();
    }

    @Test
    void getReturnsMetadataWithContent() {
        BlobKey key = key("get-meta.txt");
        PutResult result = blobStore().put(key, BlobContent.ofString("abc"),
                PutOptions.builder().contentType("text/plain").build());

        try (Blob blob = blobStore().get(key)) {
            assertThat(blob.metadata().size()).isEqualTo(3);
            assertThat(blob.metadata().etag()).isEqualTo(result.etag());
            assertThat(blob.metadata().contentType()).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/plain"));
            assertThat(blob.readUtf8()).isEqualTo("abc");
        }
    }

    @Test
    void putStoresContentTypeAndUserMetadata() {
        assumeCapability(StorageCapability.USER_METADATA);
        BlobKey key = key("meta.json");
        blobStore().put(key, BlobContent.ofString("{}"), PutOptions.builder()
                .contentType("application/json")
                .metadata("owner", "orders-service")
                .metadata("schema-version", "2")
                .build());

        BlobMetadata metadata = blobStore().head(key).orElseThrow();
        assertThat(metadata.contentType()).hasValue("application/json");
        assertThat(metadata.userMetadata())
                .containsEntry("owner", "orders-service")
                .containsEntry("schema-version", "2");
    }

    @Test
    void deleteRemovesBlob() {
        BlobKey key = key("delete.txt");
        putText(key, "bye");
        blobStore().delete(key);

        assertThat(blobStore().exists(key)).isFalse();
    }

    @Test
    void deleteMissingBlobIsIdempotent() {
        blobStore().delete(key("never-existed"));
    }

    @Test
    void unwrapUnknownTypeThrows() {
        assertThatThrownBy(() -> blobStore().unwrap(StringBuilder.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- listing -------------------------------------------------------------------------------

    @Test
    void listFiltersByPrefix() {
        putText(key("a/1"), "x");
        putText(key("a/2"), "x");
        putText(key("b/1"), "x");

        BlobPage page = blobStore().list(ListOptions.prefix(prefix + "a/"));

        assertThat(page.items()).extracting(BlobSummary::key).containsExactly(key("a/1"), key("a/2"));
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void listSummariesCarrySize() {
        putText(key("sized"), "1234");

        assertThat(blobStore().list(ListOptions.prefix(prefix)).items())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.size()).isEqualTo(4);
                    assertThat(s.lastModified()).isNotNull();
                });
    }

    @Test
    void listPaginatesInKeyOrder() {
        for (String name : List.of("e", "c", "a", "d", "b")) {
            putText(key(name), name);
        }

        List<BlobKey> seen = new ArrayList<>();
        ListOptions options = ListOptions.builder().prefix(prefix).pageSize(2).build();
        int pages = 0;
        while (options != null) {
            BlobPage page = blobStore().list(options);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
            page.items().forEach(s -> seen.add(s.key()));
            options = page.nextPageToken().map(options::withPageToken).orElse(null);
            pages++;
        }

        assertThat(seen).containsExactly(key("a"), key("b"), key("c"), key("d"), key("e"));
        assertThat(pages).isGreaterThanOrEqualTo(3);
    }

    @Test
    void listAllStreamsEveryBlob() {
        for (int i = 0; i < 7; i++) {
            putText(key("item-" + i), "x");
        }

        assertThat(blobStore().listAll(prefix)).hasSize(7);
    }

    @Test
    void listEmptyPrefixReturnsNothing() {
        assertThat(blobStore().list(ListOptions.prefix(prefix)).items()).isEmpty();
    }

    // --- folder-style listing ------------------------------------------------------------------

    @Test
    void listWithDelimiterGroupsKeysIntoPrefixes() {
        putText(key("photos/2024/a.jpg"), "x");
        putText(key("photos/2024/b.jpg"), "x");
        putText(key("photos/2025/c.jpg"), "x");
        putText(key("photos/index.html"), "x");
        putText(key("readme.txt"), "x");

        BlobPage top = blobStore().list(ListOptions.builder().prefix(prefix).delimiter("/").build());
        assertThat(top.items()).extracting(BlobSummary::key).containsExactly(key("readme.txt"));
        assertThat(top.prefixes()).containsExactly(prefix + "photos/");

        BlobPage photos = blobStore().list(ListOptions.builder().prefix(prefix + "photos/").delimiter("/").build());
        assertThat(photos.items()).extracting(BlobSummary::key).containsExactly(key("photos/index.html"));
        assertThat(photos.prefixes()).containsExactly(prefix + "photos/2024/", prefix + "photos/2025/");
    }

    @Test
    void listWithDelimiterPaginatesOverItemsAndPrefixes() {
        for (String name : List.of("a/1", "a/2", "b/1", "c", "d/1", "e")) {
            putText(key(name), "x");
        }

        List<String> blobs = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        ListOptions options = ListOptions.builder().prefix(prefix).delimiter("/").pageSize(2).build();
        while (options != null) {
            BlobPage page = blobStore().list(options);
            assertThat(page.items().size() + page.prefixes().size()).isLessThanOrEqualTo(2);
            page.items().forEach(s -> blobs.add(s.key().value()));
            prefixes.addAll(page.prefixes());
            options = page.nextPageToken().map(options::withPageToken).orElse(null);
        }

        assertThat(blobs).containsExactly(prefix + "c", prefix + "e");
        assertThat(prefixes).containsExactly(prefix + "a/", prefix + "b/", prefix + "d/");
    }

    @Test
    void listWithoutDelimiterReturnsNoPrefixes() {
        putText(key("dir/file"), "x");

        BlobPage page = blobStore().list(ListOptions.prefix(prefix));
        assertThat(page.items()).hasSize(1);
        assertThat(page.prefixes()).isEmpty();
    }

    // --- HTTP headers --------------------------------------------------------------------------

    @Test
    void putStoresStandardHttpHeaders() {
        BlobKey key = key("report.pdf");
        blobStore().put(key, BlobContent.ofString("%PDF"), PutOptions.builder()
                .contentType("application/pdf")
                .contentDisposition("attachment; filename=\"report.pdf\"")
                .contentLanguage("en-GB")
                .cacheControl("public, max-age=3600")
                .build());

        BlobHeaders headers = blobStore().head(key).orElseThrow().headers();
        assertThat(headers.contentType()).hasValue("application/pdf");
        assertThat(headers.contentDisposition()).hasValue("attachment; filename=\"report.pdf\"");
        assertThat(headers.contentLanguage()).hasValue("en-GB");
        assertThat(headers.cacheControl()).hasValue("public, max-age=3600");
        assertThat(headers.contentEncoding()).isEmpty();
    }

    @Test
    void contentEncodingIsStoredAndBytesAreReturnedUnchanged() throws Exception {
        BlobKey key = key("data.json.gz");
        byte[] gzipped = gzip("{\"compressed\":true}");
        blobStore().put(key, BlobContent.ofBytes(gzipped), PutOptions.builder()
                .contentType("application/json")
                .contentEncoding("gzip")
                .build());

        try (Blob blob = blobStore().get(key)) {
            assertThat(blob.metadata().headers().contentEncoding()).hasValue("gzip");
            assertThat(blob.readAllBytes()).isEqualTo(gzipped);
        }
    }

    @Test
    void copyPreservesHttpHeaders() {
        BlobKey source = key("styled.css");
        blobStore().put(source, BlobContent.ofString("body{}"), PutOptions.builder()
                .contentType("text/css")
                .cacheControl("no-cache")
                .build());

        blobStore().copy(source, key("styled-copy.css"));

        BlobHeaders headers = blobStore().head(key("styled-copy.css")).orElseThrow().headers();
        assertThat(headers.contentType()).hasValue("text/css");
        assertThat(headers.cacheControl()).hasValue("no-cache");
    }

    // --- streamed and multipart uploads --------------------------------------------------------

    /** Larger than two minimum-size parts, so it takes three parts at the 5 MiB part size. */
    private static final int LARGE_SIZE = 2 * PutOptions.MIN_PART_SIZE + 123_457;

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private static PutOptions.Builder minimumParts() {
        return PutOptions.builder().partSize(PutOptions.MIN_PART_SIZE);
    }

    @Test
    void writeSmallBlobInOneRequest() {
        BlobKey key = key("small-write.txt");

        PutResult result = blobStore().write(key, out -> out.write("small".getBytes(StandardCharsets.UTF_8)));

        assertThat(readText(key)).isEqualTo("small");
        assertThat(blobStore().head(key)).get().extracting(BlobMetadata::etag).isEqualTo(result.etag());
    }

    @Test
    void writeEmptyBlob() {
        BlobKey key = key("empty-write");
        blobStore().write(key, out -> { });

        assertThat(blobStore().getBytes(key)).isEmpty();
    }

    @Test
    void writeLargeBlobInParts() {
        BlobKey key = key("large-write.bin");
        byte[] data = randomBytes(LARGE_SIZE);

        PutResult result = blobStore().write(key, out -> {
            // Odd-sized writes exercise part boundaries.
            for (int off = 0; off < data.length; off += 1_000_003) {
                out.write(data, off, Math.min(1_000_003, data.length - off));
            }
        }, minimumParts().contentType("application/octet-stream").metadata("kind", "large").build());

        BlobMetadata metadata = blobStore().head(key).orElseThrow();
        assertThat(metadata.size()).isEqualTo(LARGE_SIZE);
        assertThat(metadata.etag()).isEqualTo(result.etag());
        assertThat(metadata.contentType()).hasValue("application/octet-stream");
        assertThat(metadata.userMetadata()).containsEntry("kind", "large");
        assertThat(blobStore().getBytes(key)).isEqualTo(data);
    }

    @Test
    void writeExactMultipleOfPartSize() {
        BlobKey key = key("exact-parts.bin");
        byte[] data = randomBytes(2 * PutOptions.MIN_PART_SIZE);

        blobStore().write(key, out -> out.write(data), minimumParts().build());

        assertThat(blobStore().getBytes(key)).isEqualTo(data);
    }

    @Test
    void failedWriteStoresNothing() {
        BlobKey key = key("failed-write.bin");

        assertThatThrownBy(() -> blobStore().write(key, out -> {
            out.write(randomBytes(PutOptions.MIN_PART_SIZE + 1));
            throw new IOException("source failed");
        }, minimumParts().build()))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("source failed");
        assertThat(blobStore().exists(key)).isFalse();
    }

    @Test
    void failedWriteKeepsExistingBlob() {
        BlobKey key = key("keep-on-failure.txt");
        putText(key, "original");

        assertThatThrownBy(() -> blobStore().write(key, out -> {
            out.write(randomBytes(PutOptions.MIN_PART_SIZE + 1));
            throw new IllegalStateException("producer failed");
        }, minimumParts().build())).isInstanceOf(IllegalStateException.class);

        assertThat(readText(key)).isEqualTo("original");
    }

    @Test
    void closingTheStreamInsideWriteDoesNotCommitEarly() {
        BlobKey key = key("wrapped.gz");

        blobStore().write(key, out -> {
            try (var gzip = new GZIPOutputStream(out)) {
                gzip.write("wrapped".getBytes(StandardCharsets.UTF_8));
            }
        });

        assertThat(gunzip(blobStore().getBytes(key))).isEqualTo("wrapped");
    }

    @Test
    void writeInPartsWithIfNotExistsRejectsExistingBlob() {
        assumeCapability(StorageCapability.CONDITIONAL_WRITE);
        BlobKey key = key("large-create-only.bin");
        putText(key, "v1");

        assertThatThrownBy(() -> blobStore().write(key, out -> out.write(randomBytes(LARGE_SIZE)),
                minimumParts().ifNotExists().build()))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(readText(key)).isEqualTo("v1");
    }

    @Test
    void putUnknownLengthStream() {
        BlobKey key = key("unknown-length.bin");
        byte[] data = randomBytes(LARGE_SIZE);

        blobStore().put(key, BlobContent.ofStream(new ByteArrayInputStream(data)), minimumParts().build());

        assertThat(blobStore().getBytes(key)).isEqualTo(data);
    }

    @Test
    void putSmallUnknownLengthStream() {
        BlobKey key = key("unknown-small.txt");

        blobStore().put(key, BlobContent.ofStream(new ByteArrayInputStream("tiny".getBytes(StandardCharsets.UTF_8))));

        assertThat(readText(key)).isEqualTo("tiny");
    }

    @Test
    void putLargeFile(@TempDir Path dir) throws Exception {
        byte[] data = randomBytes(LARGE_SIZE);
        Path file = Files.write(dir.resolve("large.bin"), data);
        BlobKey key = key("large-file.bin");

        blobStore().put(key, BlobContent.ofFile(file), minimumParts().build());

        assertThat(blobStore().getBytes(key)).isEqualTo(data);
    }

    private static byte[] gzip(String text) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String gunzip(byte[] data) {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- checksums -----------------------------------------------------------------------------

    @Test
    void validatedPutStoresAChecksumThatValidatesOnDownload() {
        assumeCapability(StorageCapability.CHECKSUM);
        BlobKey key = key("checked.txt");

        blobStore().put(key, BlobContent.ofString("integrity matters"), PutOptions.builder().validateChecksum().build());

        assertThat(blobStore().head(key).orElseThrow().checksums().preferred()).isPresent();
        try (Blob blob = blobStore().get(key, GetOptions.builder().validateChecksum().build())) {
            assertThat(blob.readUtf8()).isEqualTo("integrity matters");
        }
    }

    @Test
    void validatedFileAndStreamUploads(@TempDir Path dir) throws Exception {
        assumeCapability(StorageCapability.CHECKSUM);
        byte[] data = randomBytes(300_000);
        Path file = Files.write(dir.resolve("checked.bin"), data);
        PutOptions validated = PutOptions.builder().validateChecksum().build();

        blobStore().put(key("from-file"), BlobContent.ofFile(file), validated);
        blobStore().put(key("from-stream"), BlobContent.ofStream(new ByteArrayInputStream(data), data.length), validated);

        for (String name : List.of("from-file", "from-stream")) {
            try (Blob blob = blobStore().get(key(name), GetOptions.builder().validateChecksum().build())) {
                assertThat(blob.readAllBytes()).isEqualTo(data);
            }
        }
    }

    @Test
    void validatedWriteInParts() {
        assumeCapability(StorageCapability.CHECKSUM);
        BlobKey key = key("checked-large.bin");
        byte[] data = randomBytes(LARGE_SIZE);

        blobStore().write(key, out -> out.write(data), minimumParts().validateChecksum().build());

        try (Blob blob = blobStore().get(key, GetOptions.builder().validateChecksum().build())) {
            assertThat(blob.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void storedChecksumsMatchTheContent() {
        assumeCapability(StorageCapability.CHECKSUM);
        BlobKey key = key("known-checksum.txt");
        byte[] data = "123456789".getBytes(StandardCharsets.UTF_8);

        blobStore().put(key, BlobContent.ofBytes(data), PutOptions.builder().validateChecksum().build());

        Checksums stored = blobStore().head(key).orElseThrow().checksums();
        // CRC32C("123456789") = 0xE3069283, MD5 = 25f9e794323b453885f5181f1b624d0b
        stored.crc32c().ifPresent(v -> assertThat(v).isEqualTo("4waSgw=="));
        stored.md5().ifPresent(v -> assertThat(v).isEqualTo("JfnnlDI7RTiF9RgfG2JNCw=="));
    }

    // --- conditional and batch delete ----------------------------------------------------------

    @Test
    void deleteIfMatchWithCurrentETagDeletes() {
        assumeCapability(StorageCapability.CONDITIONAL_DELETE);
        BlobKey key = key("cond-delete.txt");
        PutResult v1 = putText(key, "v1");

        blobStore().delete(key, DeleteOptions.ifMatch(v1.etag()));

        assertThat(blobStore().exists(key)).isFalse();
    }

    @Test
    void deleteIfMatchWithStaleETagFails() {
        assumeCapability(StorageCapability.CONDITIONAL_DELETE);
        BlobKey key = key("cond-delete-stale.txt");
        PutResult v1 = putText(key, "v1");
        putText(key, "v2");

        assertThatThrownBy(() -> blobStore().delete(key, DeleteOptions.ifMatch(v1.etag())))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(readText(key)).isEqualTo("v2");
    }

    @Test
    void deleteIfMatchOnMissingBlobFails() {
        assumeCapability(StorageCapability.CONDITIONAL_DELETE);
        BlobKey key = key("cond-delete-missing.txt");
        PutResult v1 = putText(key, "v1");
        blobStore().delete(key);

        assertThatThrownBy(() -> blobStore().delete(key, DeleteOptions.ifMatch(v1.etag())))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void deleteAllRemovesEveryKeyIncludingMissingOnes() {
        List<BlobKey> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            keys.add(key("batch/" + i));
            putText(keys.get(i), "x");
        }
        keys.add(key("batch/never-existed"));

        BatchDeleteResult result = blobStore().deleteAll(keys);

        assertThat(result.isComplete()).as("failures: %s", result.failed()).isTrue();
        assertThat(result.deleted()).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(blobStore().list(ListOptions.prefix(prefix + "batch/")).items()).isEmpty();
    }

    @Test
    void deleteAllSpansSeveralProviderBatches() {
        List<BlobKey> keys = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            keys.add(key(String.format("many/%03d", i)));
            blobStore().put(keys.get(i), BlobContent.ofBytes(new byte[] {(byte) i}));
        }

        BatchDeleteResult result = blobStore().deleteAll(keys);

        assertThat(result.isComplete()).as("failures: %s", result.failed()).isTrue();
        assertThat(result.deleted()).hasSize(260);
        assertThat(blobStore().listAll(prefix + "many/")).isEmpty();
    }

    @Test
    void deleteAllWithNoKeys() {
        BatchDeleteResult result = blobStore().deleteAll(List.of());

        assertThat(result.deleted()).isEmpty();
        assertThat(result.isComplete()).isTrue();
    }

    // --- metadata updates ----------------------------------------------------------------------

    @Test
    void updateMetadataReplacesHeadersAndMetadataButNotContent() {
        BlobKey key = key("update-meta.txt");
        blobStore().put(key, BlobContent.ofString("unchanged"), PutOptions.builder()
                .contentType("text/plain")
                .metadata("keep", "1")
                .metadata("drop", "2")
                .build());
        BlobMetadata current = blobStore().head(key).orElseThrow();

        BlobMetadata updated = blobStore().updateMetadata(key, MetadataUpdate.from(current)
                .cacheControl("no-cache")
                .contentDisposition("inline")
                .removeMetadata("drop")
                .metadata("added", "3")
                .build());

        assertThat(updated.headers().contentType()).hasValue("text/plain");
        assertThat(updated.headers().cacheControl()).hasValue("no-cache");
        assertThat(updated.userMetadata()).containsOnlyKeys("keep", "added");
        BlobMetadata reread = blobStore().head(key).orElseThrow();
        assertThat(reread.headers().cacheControl()).hasValue("no-cache");
        assertThat(reread.headers().contentDisposition()).hasValue("inline");
        assertThat(reread.userMetadata()).containsEntry("keep", "1").containsEntry("added", "3").doesNotContainKey("drop");
        assertThat(readText(key)).isEqualTo("unchanged");
    }

    @Test
    void updateMetadataWithStaleETagFails() {
        BlobKey key = key("update-stale.txt");
        PutResult v1 = putText(key, "v1");
        putText(key, "v2");

        assertThatThrownBy(() -> blobStore().updateMetadata(key, MetadataUpdate.builder()
                .cacheControl("no-cache").ifMatch(v1.etag()).build()))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void updateMetadataOfMissingBlobThrowsNotFound() {
        assertThatThrownBy(() -> blobStore().updateMetadata(key("missing"),
                MetadataUpdate.builder().cacheControl("no-cache").build()))
                .isInstanceOf(NotFoundException.class);
    }

    // --- storage tiers -------------------------------------------------------------------------

    @Test
    void defaultTierIsHot() {
        assumeCapability(StorageCapability.STORAGE_TIERS);
        BlobKey key = key("default-tier.txt");
        putText(key, "x");

        assertThat(blobStore().head(key).orElseThrow().tier()).hasValue(StorageTier.HOT);
    }

    @Test
    void putWithTierStoresTier() {
        assumeCapability(StorageCapability.STORAGE_TIERS);
        BlobKey key = key("cool.txt");
        blobStore().put(key, BlobContent.ofString("cool"), PutOptions.builder().tier(StorageTier.COOL).build());

        assertThat(blobStore().head(key).orElseThrow().tier()).hasValue(StorageTier.COOL);
        assertThat(blobStore().list(ListOptions.prefix(prefix)).items())
                .singleElement().satisfies(s -> assertThat(s.tier()).hasValue(StorageTier.COOL));
        assertThat(readText(key)).isEqualTo("cool");
    }

    @Test
    void setTierChangesTierButNotContent() {
        assumeCapability(StorageCapability.STORAGE_TIERS);
        BlobKey key = key("retier.txt");
        blobStore().put(key, BlobContent.ofString("content"), PutOptions.builder().contentType("text/plain").build());

        blobStore().setTier(key, StorageTier.COLD);

        BlobMetadata metadata = blobStore().head(key).orElseThrow();
        assertThat(metadata.tier()).hasValue(StorageTier.COLD);
        assertThat(metadata.contentType()).hasValue("text/plain");
        assertThat(readText(key)).isEqualTo("content");
    }

    @Test
    void updateMetadataKeepsTier() {
        assumeCapability(StorageCapability.STORAGE_TIERS);
        BlobKey key = key("tier-kept.txt");
        blobStore().put(key, BlobContent.ofString("x"), PutOptions.builder().tier(StorageTier.COOL).build());

        blobStore().updateMetadata(key, MetadataUpdate.builder().cacheControl("no-cache").build());

        assertThat(blobStore().head(key).orElseThrow().tier()).hasValue(StorageTier.COOL);
    }

    // --- range reads ---------------------------------------------------------------------------

    @Test
    void rangeReadReturnsSlice() {
        assumeCapability(StorageCapability.RANGE_READ);
        BlobKey key = key("range.txt");
        putText(key, "hello world");

        try (Blob blob = blobStore().get(key, GetOptions.range(ByteRange.of(6, 5)))) {
            assertThat(blob.readUtf8()).isEqualTo("world");
            assertThat(blob.metadata().size()).isEqualTo(11);
        }
    }

    @Test
    void openEndedRangeReadsToEnd() {
        assumeCapability(StorageCapability.RANGE_READ);
        BlobKey key = key("range-open.txt");
        putText(key, "hello world");

        try (Blob blob = blobStore().get(key, GetOptions.range(ByteRange.from(6)))) {
            assertThat(blob.readUtf8()).isEqualTo("world");
        }
    }

    // --- copy ----------------------------------------------------------------------------------

    @Test
    void copyDuplicatesContentAndKeepsSource() {
        BlobKey source = key("source.txt");
        BlobKey target = key("target.txt");
        putText(source, "copy me");

        PutResult result = blobStore().copy(source, target);

        assertThat(result.key()).isEqualTo(target);
        assertThat(readText(target)).isEqualTo("copy me");
        assertThat(readText(source)).isEqualTo("copy me");
    }

    @Test
    void copyMissingSourceThrowsNotFound() {
        assertThatThrownBy(() -> blobStore().copy(key("missing"), key("target")))
                .isInstanceOf(NotFoundException.class);
    }

    // --- conditional writes --------------------------------------------------------------------

    @Test
    void ifNotExistsCreatesMissingBlob() {
        assumeCapability(StorageCapability.CONDITIONAL_WRITE);
        BlobKey key = key("create-only.txt");
        blobStore().put(key, BlobContent.ofString("v1"), PutOptions.builder().ifNotExists().build());

        assertThat(readText(key)).isEqualTo("v1");
    }

    @Test
    void ifNotExistsRejectsExistingBlob() {
        assumeCapability(StorageCapability.CONDITIONAL_WRITE);
        BlobKey key = key("create-only.txt");
        putText(key, "v1");

        assertThatThrownBy(() -> blobStore().put(key, BlobContent.ofString("v2"),
                PutOptions.builder().ifNotExists().build()))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(readText(key)).isEqualTo("v1");
    }

    @Test
    void ifMatchWithCurrentETagSucceeds() {
        assumeCapability(StorageCapability.CONDITIONAL_WRITE);
        BlobKey key = key("cas.txt");
        PutResult v1 = putText(key, "v1");

        blobStore().put(key, BlobContent.ofString("v2"), PutOptions.builder().ifMatch(v1.etag()).build());

        assertThat(readText(key)).isEqualTo("v2");
    }

    @Test
    void ifMatchWithStaleETagThrowsPreconditionFailed() {
        assumeCapability(StorageCapability.CONDITIONAL_WRITE);
        BlobKey key = key("cas.txt");
        PutResult v1 = putText(key, "v1");
        putText(key, "v2");

        assertThatThrownBy(() -> blobStore().put(key, BlobContent.ofString("v3"),
                PutOptions.builder().ifMatch(v1.etag()).build()))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(readText(key)).isEqualTo("v2");
    }

    @Test
    void getWithStaleETagThrowsPreconditionFailed() {
        BlobKey key = key("get-cas.txt");
        PutResult v1 = putText(key, "v1");
        putText(key, "v2");

        assertThatThrownBy(() -> {
            try (Blob blob = blobStore().get(key, GetOptions.builder().ifMatch(v1.etag()).build())) {
                blob.readAllBytes();
            }
        }).isInstanceOf(PreconditionFailedException.class);
    }

    // --- presigned URLs ------------------------------------------------------------------------

    @Test
    void presignedDownloadUrlDownloadsBlob() throws Exception {
        assumeCapability(StorageCapability.PRESIGNED_URL);
        BlobKey key = key("presigned-download.txt");
        putText(key, "shared via url");

        PresignedUrl url = blobStore().presignDownload(key, Duration.ofMinutes(5));
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.noBody());

        assertThat(url.method()).isEqualTo(PresignRequest.Method.GET);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("shared via url");
    }

    @Test
    void presignedUploadUrlUploadsBlob() throws Exception {
        assumeCapability(StorageCapability.PRESIGNED_URL);
        BlobKey key = key("presigned-upload.txt");

        PresignedUrl url = blobStore().presignUpload(key, Duration.ofMinutes(5));
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.ofString("uploaded via url"));

        assertThat(url.method()).isEqualTo(PresignRequest.Method.PUT);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertThat(readText(key)).isEqualTo("uploaded via url");
    }

    @Test
    void presignedUploadUrlReplacesExistingBlob() throws Exception {
        assumeCapability(StorageCapability.PRESIGNED_URL);
        BlobKey key = key("presigned-replace.txt");
        putText(key, "old");

        HttpResponse<String> response = PresignedRequests.send(blobStore().presignUpload(key, Duration.ofMinutes(5)),
                HttpRequest.BodyPublishers.ofString("new"));

        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertThat(readText(key)).isEqualTo("new");
    }

    @Test
    void presignedUploadWithContentTypeSetsContentType() throws Exception {
        assumeCapability(StorageCapability.PRESIGNED_URL);
        BlobKey key = key("presigned-typed.json");

        PresignedUrl url = blobStore().presignUpload(key, Duration.ofMinutes(5), "application/json");
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.ofString("{\"ok\":true}"));

        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertThat(blobStore().head(key)).get()
                .satisfies(m -> assertThat(m.contentType()).hasValue("application/json"));
    }

    @Test
    void presignedUrlReportsExpiry() {
        assumeCapability(StorageCapability.PRESIGNED_URL);
        Instant before = Instant.now();

        PresignedUrl url = blobStore().presignDownload(key("expiry.txt"), Duration.ofMinutes(10));

        assertThat(url.expiresAt()).isBetween(before.plus(Duration.ofMinutes(9)), Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(url.isExpired()).isFalse();
    }

    @Test
    void presignWithoutCapabilityIsRejected() {
        assumeTrue(!blobStore().capabilities().supports(StorageCapability.PRESIGNED_URL));

        assertThatThrownBy(() -> blobStore().presignUpload(key("x"), Duration.ofMinutes(1)))
                .isInstanceOf(UnsupportedFeatureException.class);
    }

}
