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

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.storage.spi.BufferPool;
import dev.halyx.storage.spi.ChecksumCalculator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The portable {@link BlobStore}, the abstraction side of the Bridge. It applies capability checks,
 * the interceptor chain and exception translation around a provider {@link BlobStoreDriver}.
 *
 * <p>Created by {@link dev.halyx.CloudContext}; applications use the {@link BlobStore}
 * interface.
 */
public final class DefaultBlobStore implements BlobStore {

    private static final String SERVICE = "storage";

    private final String provider;
    private final String bucket;
    private final BlobStoreDriver driver;
    private final InterceptorChain interceptors;

    public DefaultBlobStore(String provider, String bucket, BlobStoreDriver driver, InterceptorChain interceptors) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return provider;
    }

    /** Batch requests {@link #deleteAll} keeps in flight at once. */
    static final int DELETE_BATCH_CONCURRENCY = 4;
    /** Single deletes {@link #deleteAll} keeps in flight on providers without a batch API. */
    static final int DELETE_CONCURRENCY = 16;

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public PutResult put(BlobKey key, BlobContent content, PutOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(content, "content");
        checkWrite(options);
        return switch (content) {
            // A one-shot stream can only be checksummed while it is uploaded, which the part path does.
            case BlobContent.OfStream stream when options.validateChecksum() -> write(key, stream.stream()::transferTo, options);
            // Checksumming a large file before uploading it would read it twice: the part path reads it once.
            case BlobContent.OfFile file when options.validateChecksum() && file.length() > options.partSize() ->
                    write(key, out -> Files.copy(file.path(), out), options);
            case BlobContent.Sized sized -> execute("put", key, sized.repeatable(), () -> {
                BlobContent.Sized body = options.validateChecksum() && sized instanceof BlobContent.OfFile file
                        ? new BlobContent.OfBytes(readFile(file)) : sized;
                Checksums expected = options.validateChecksum() ? checksumsOf(body) : Checksums.none();
                return verifyWrite(driver, key, driver.put(key, body, options, expected), expected);
            });
            case BlobContent.OfUnsizedStream stream -> write(key, stream.stream()::transferTo, options);
        };
    }

    @Override
    public PutResult write(BlobKey key, BlobSink sink, PutOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sink, "sink");
        checkWrite(options);
        OperationContext context = new OperationContext(SERVICE, "write", provider, bucket + "/" + key, false);
        return interceptors.execute(context, () -> {
            ChunkingOutputStream out = new ChunkingOutputStream(driver, key, options);
            try {
                sink.writeTo(out);
            } catch (IOException e) {
                out.abort();
                throw new UncheckedIOException(e);
            } catch (RuntimeException | Error e) {
                out.abort();
                throw e;
            }
            PutResult result = out.finish();
            return verifyWrite(driver, key, result, out.expectedChecksums());
        });
    }

    private void checkWrite(PutOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.isConditional()) {
            require(StorageCapability.CONDITIONAL_WRITE);
        }
        if (!options.metadata().isEmpty()) {
            require(StorageCapability.USER_METADATA);
        }
        if (options.tier().isPresent()) {
            require(StorageCapability.STORAGE_TIERS);
        }
        if (options.validateChecksum()) {
            require(StorageCapability.CHECKSUM);
        }
    }

    /**
     * Compares what core computed with what the provider reports it stored. On a mismatch the corrupt
     * blob is removed (only if it is still the version just written) and the write fails.
     */
    static PutResult verifyWrite(BlobStoreDriver driver, BlobKey key, PutResult result, Checksums expected) {
        if (expected.isEmpty()) {
            return result;
        }
        var mismatched = expected.mismatches(result.checksums());
        if (mismatched.isEmpty()) {
            return result;
        }
        if (driver.capabilities().supports(StorageCapability.CONDITIONAL_DELETE)) {
            try {
                driver.delete(key, DeleteOptions.ifMatch(result.etag()));
            } catch (RuntimeException ignored) {
                // Replaced concurrently, or already gone: nothing of ours to clean up.
            }
        }
        throw new DataIntegrityException("Upload of " + key + " failed " + mismatched + " validation: sent "
                + expected.values() + ", provider stored " + result.checksums().values());
    }

    /** Validated puts reach here only with bytes (files are read first, streams take the part path). */
    private Checksums checksumsOf(BlobContent.Sized content) {
        if (!(content instanceof BlobContent.OfBytes bytes)) {
            throw new IllegalStateException("Unexpected content for a validated put: " + content);
        }
        return ChecksumCalculator.of(driver.uploadChecksums(), bytes.bytes(), 0, bytes.bytes().length);
    }

    /** A file of at most one part, read once into memory so it is checksummed and sent from the same bytes. */
    private static byte[] readFile(BlobContent.OfFile file) {
        try {
            return Files.readAllBytes(file.path());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        if (options.range().isPresent()) {
            require(StorageCapability.RANGE_READ);
        }
        if (options.validateChecksum()) {
            require(StorageCapability.CHECKSUM);
        }
        Blob blob = options.parallelism() > 1 && driver.capabilities().supports(StorageCapability.RANGE_READ)
                ? parallelGet(key, options)
                : execute("get", key, true, () -> driver.get(key, options));
        if (!options.validateChecksum()) {
            return blob;
        }
        Checksums stored = blob.metadata().checksums();
        Optional<ChecksumAlgorithm> algorithm = stored.preferred();
        if (algorithm.isEmpty()) {
            blob.close();
            throw new UnsupportedFeatureException("Blob " + key + " has no stored checksum to validate against; "
                    + "write it with PutOptions.validateChecksum()", null, provider, null);
        }
        String expected = stored.get(algorithm.get()).orElseThrow();
        return new Blob(blob.metadata(), new VerifyingInputStream(blob.content(), algorithm.get(), expected, provider));
    }

    /**
     * Looks the blob up once, then reads it as concurrent ranges pinned to that version. Content of at most
     * one part is a single pinned read, so small blobs pay no more than one extra lookup.
     */
    private Blob parallelGet(BlobKey key, GetOptions options) {
        BlobMetadata metadata = head(key).orElseThrow(() ->
                new NotFoundException("No blob " + bucket + "/" + key, null, provider, null));
        if (options.ifMatch().isPresent() && !options.ifMatch().get().equals(metadata.etag())) {
            throw new PreconditionFailedException("Blob " + key + " has changed", null, provider, null);
        }
        long start = options.range().map(ByteRange::offset).orElse(0L);
        long end = options.range().flatMap(r -> r.length().isPresent()
                        ? Optional.of(Math.min(metadata.size(), r.offset() + r.length().getAsLong()))
                        : Optional.empty())
                .orElse(metadata.size());
        if (end - start <= options.partSize()) {
            var single = GetOptions.builder().ifMatch(metadata.etag());
            options.range().ifPresent(single::range);
            Blob blob = execute("get", key, true, () -> driver.get(key, single.build()));
            return new Blob(metadata, blob.content());
        }
        var content = new ParallelRangeInputStream(start, end, options.partSize(), options.parallelism(),
                BufferPool.shared(), (offset, buffer, length) -> fetchPart(key, metadata.etag(), offset, buffer, length));
        return new Blob(metadata, content);
    }

    /** One range of a parallel read; retried as a whole (request and body) by the interceptors. */
    private void fetchPart(BlobKey key, ETag etag, long offset, byte[] buffer, int length) {
        try {
            readPart(key, etag, offset, buffer, length);
        } catch (NotFoundException e) {
            throw new PreconditionFailedException("Blob " + key + " changed or was deleted during a parallel read",
                    e, provider, null);
        }
    }

    private void readPart(BlobKey key, ETag etag, long offset, byte[] buffer, int length) {
        execute("getPart", key, true, () -> {
            try (InputStream in = driver.readRange(key, etag, offset, length)) {
                int read = in.readNBytes(buffer, 0, length);
                if (read != length) {
                    throw new DataIntegrityException("Short read of " + key + " at " + offset + ": expected "
                            + length + " bytes, got " + read);
                }
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public BlobMetadata download(BlobKey key, Path target, GetOptions options) {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath();
        Path directory = absolute.getParent();
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "." + absolute.getFileName(), ".part");
            try (Blob blob = get(key, options); OutputStream out = Files.newOutputStream(temp)) {
                blob.content().transferTo(out);
                out.close();
                moveIntoPlace(temp, absolute);
                return blob.metadata();
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The target appears complete or not at all. */
    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        Objects.requireNonNull(key, "key");
        return execute("head", key, true, () -> driver.head(key));
    }

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        if (options.isConditional()) {
            require(StorageCapability.CONDITIONAL_DELETE);
        }
        execute("delete", key, true, () -> {
            driver.delete(key, options);
            return null;
        });
    }

    @Override
    public BatchDeleteResult deleteAll(Collection<BlobKey> keys) {
        Objects.requireNonNull(keys, "keys");
        List<BlobKey> unique = List.copyOf(new LinkedHashSet<>(keys));
        if (!capabilities().supports(StorageCapability.BATCH_DELETE)) {
            return deleteOneByOne(unique);
        }
        int size = driver.maxBatchSize();
        List<List<BlobKey>> batches = new ArrayList<>();
        for (int from = 0; from < unique.size(); from += size) {
            batches.add(unique.subList(from, Math.min(from + size, unique.size())));
        }
        return BatchDeleteResult.merge(Bounded.map(batches, DELETE_BATCH_CONCURRENCY, this::deleteBatch));
    }

    private BatchDeleteResult deleteBatch(List<BlobKey> batch) {
        OperationContext context = new OperationContext(SERVICE, "deleteBatch", provider,
                bucket + "/[" + batch.size() + " keys]", true);
        try {
            BatchDeleteResult result = interceptors.execute(context, () -> translated(() -> driver.deleteBatch(batch)));
            // An endpoint without the batch API (an emulator or S3-compatible store) rejects every
            // sub-request as not implemented: delete those keys individually instead.
            if (!result.failed().isEmpty()
                    && result.failed().values().stream().allMatch(UnsupportedFeatureException.class::isInstance)) {
                Set<BlobKey> unsupported = result.failed().keySet();
                result = BatchDeleteResult.merge(List.of(
                        new BatchDeleteResult(result.deleted(), Map.of()),
                        deleteOneByOne(batch.stream().filter(unsupported::contains).toList())));
            }
            return result;
        } catch (UnsupportedFeatureException e) {
            return deleteOneByOne(batch);
        } catch (CloudException e) {
            // The whole request failed (for example access denied): report it against every key.
            Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
            batch.forEach(k -> failed.put(k, e));
            return new BatchDeleteResult(List.of(), failed);
        }
    }

    /** Without a batch API, keys are deleted with up to {@value #DELETE_CONCURRENCY} requests at once. */
    private BatchDeleteResult deleteOneByOne(List<BlobKey> keys) {
        return BatchDeleteResult.merge(Bounded.map(keys, DELETE_CONCURRENCY, key -> {
            try {
                delete(key);
                return new BatchDeleteResult(List.of(key), Map.of());
            } catch (CloudException e) {
                return new BatchDeleteResult(List.of(), Map.of(key, e));
            }
        }));
    }

    @Override
    public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(update, "update");
        if (!update.metadata().isEmpty()) {
            require(StorageCapability.USER_METADATA);
        }
        return execute("updateMetadata", key, true, () -> driver.updateMetadata(key, update));
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tier, "tier");
        require(StorageCapability.STORAGE_TIERS);
        execute("setTier", key, true, () -> {
            driver.setTier(key, tier);
            return null;
        });
    }

    @Override
    public BlobPage list(ListOptions options) {
        Objects.requireNonNull(options, "options");
        OperationContext context = new OperationContext(SERVICE, "list", provider,
                bucket + "/" + options.prefix().orElse(""), true);
        return interceptors.execute(context, () -> translated(() -> driver.list(options)));
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (capabilities().supports(StorageCapability.SERVER_SIDE_COPY)) {
            try {
                return execute("copy", target, true, () -> driver.copy(source, target));
            } catch (UnsupportedFeatureException e) {
                // The endpoint (e.g. an emulator or S3-compatible store) lacks server-side copy.
            }
        }
        return clientSideCopy(source, target);
    }

    private PutResult clientSideCopy(BlobKey source, BlobKey target) {
        try (Blob blob = get(source)) {
            BlobMetadata metadata = blob.metadata();
            PutOptions options = PutOptions.builder().headers(metadata.headers()).metadata(metadata.userMetadata()).build();
            return put(target, BlobContent.ofStream(blob.content(), metadata.size()), options);
        }
    }

    @Override
    public PresignedUpload startPresignedUpload(BlobKey key, PutOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        require(StorageCapability.PRESIGNED_MULTIPART);
        if (options.isConditional() || options.validateChecksum()) {
            throw new InvalidArgumentException("Presigned uploads support neither conditional writes nor "
                    + "checksum validation: the bytes go straight from the client to storage", null, provider, null);
        }
        if (!options.metadata().isEmpty()) {
            require(StorageCapability.USER_METADATA);
        }
        if (options.tier().isPresent()) {
            require(StorageCapability.STORAGE_TIERS);
        }
        // Creating an upload is not idempotent (each call starts a new one), so it is never retried.
        return execute("startPresignedUpload", key, false, () -> driver.startPresignedUpload(key, options));
    }

    @Override
    public PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(expiry, "expiry");
        require(StorageCapability.PRESIGNED_MULTIPART);
        if (partNumber < 1 || partNumber > PresignedUploads.MAX_PARTS) {
            throw new IllegalArgumentException("partNumber must be between 1 and " + PresignedUploads.MAX_PARTS);
        }
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("expiry must be positive");
        }
        return execute("presignUploadPart", upload.key(), true,
                () -> driver.presignUploadPart(upload, partNumber, expiry));
    }

    @Override
    public PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(parts, "parts");
        require(StorageCapability.PRESIGNED_MULTIPART);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("parts must not be empty");
        }
        List<UploadedPart> sorted = parts.stream().sorted(Comparator.comparingInt(UploadedPart::partNumber)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).partNumber() == sorted.get(i - 1).partNumber()) {
                throw new IllegalArgumentException("Duplicate part number " + sorted.get(i).partNumber());
            }
        }
        return execute("completePresignedUpload", upload.key(), true,
                () -> driver.completePresignedUpload(upload, sorted));
    }

    @Override
    public void abortPresignedUpload(PresignedUpload upload) {
        Objects.requireNonNull(upload, "upload");
        require(StorageCapability.PRESIGNED_MULTIPART);
        execute("abortPresignedUpload", upload.key(), true, () -> {
            driver.abortPresignedUpload(upload);
            return null;
        });
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        require(StorageCapability.PRESIGNED_URL);
        return execute("presign", key, true, () -> driver.presign(key, request));
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(Objects.requireNonNull(nativeType, "nativeType"));
    }

    private void require(StorageCapability capability) {
        capabilities().require(capability, provider);
    }

    private <T> T execute(String operation, BlobKey key, boolean idempotent, Supplier<T> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider, bucket + "/" + key, idempotent);
        return interceptors.execute(context, () -> translated(call));
    }

    /** Translates native errors before interceptors see them, so they stay provider-neutral. */
    private <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw driver.translate(e);
        }
    }
}
