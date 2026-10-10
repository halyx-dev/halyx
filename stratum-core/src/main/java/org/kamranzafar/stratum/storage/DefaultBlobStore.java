package org.kamranzafar.stratum.storage;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.DataIntegrityException;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;
import org.kamranzafar.stratum.interceptor.InterceptorChain;
import org.kamranzafar.stratum.interceptor.OperationContext;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;
import org.kamranzafar.stratum.storage.spi.ChecksumCalculator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>Created by {@link org.kamranzafar.stratum.CloudContext}; applications use the {@link BlobStore}
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
            case BlobContent.Sized sized -> execute("put", key, sized.repeatable(), () -> {
                Checksums expected = options.validateChecksum() ? checksumsOf(sized) : Checksums.none();
                return verifyWrite(driver, key, driver.put(key, sized, options, expected), expected);
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

    private static Checksums checksumsOf(BlobContent.Sized content) {
        if (content instanceof BlobContent.OfBytes bytes) {
            return ChecksumCalculator.of(bytes.bytes(), 0, bytes.bytes().length);
        }
        ChecksumCalculator calculator = ChecksumCalculator.forUpload();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = content.openStream()) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                calculator.update(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return calculator.result();
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
        Blob blob = execute("get", key, true, () -> driver.get(key, options));
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
        List<BatchDeleteResult> results = new ArrayList<>();
        for (int from = 0; from < unique.size(); from += size) {
            List<BlobKey> batch = unique.subList(from, Math.min(from + size, unique.size()));
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
                results.add(result);
            } catch (UnsupportedFeatureException e) {
                results.add(deleteOneByOne(batch));
            } catch (CloudException e) {
                // The whole request failed (for example access denied): report it against every key.
                Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
                batch.forEach(k -> failed.put(k, e));
                results.add(new BatchDeleteResult(List.of(), failed));
            }
        }
        return BatchDeleteResult.merge(results);
    }

    private BatchDeleteResult deleteOneByOne(List<BlobKey> keys) {
        List<BlobKey> deleted = new ArrayList<>();
        Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
        for (BlobKey key : keys) {
            try {
                delete(key);
                deleted.add(key);
            } catch (CloudException e) {
                failed.put(key, e);
            }
        }
        return new BatchDeleteResult(deleted, failed);
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
