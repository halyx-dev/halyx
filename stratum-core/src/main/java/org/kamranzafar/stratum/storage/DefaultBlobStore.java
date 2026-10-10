package org.kamranzafar.stratum.storage;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;
import org.kamranzafar.stratum.interceptor.InterceptorChain;
import org.kamranzafar.stratum.interceptor.OperationContext;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
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
        Objects.requireNonNull(options, "options");
        if (options.isConditional()) {
            require(StorageCapability.CONDITIONAL_WRITE);
        }
        if (!options.metadata().isEmpty()) {
            require(StorageCapability.USER_METADATA);
        }
        return switch (content) {
            case BlobContent.Sized sized -> execute("put", key, sized.repeatable(), () -> driver.put(key, sized, options));
            case BlobContent.OfUnsizedStream stream -> write(key, stream.stream()::transferTo, options);
        };
    }

    @Override
    public PutResult write(BlobKey key, BlobSink sink, PutOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(options, "options");
        if (options.isConditional()) {
            require(StorageCapability.CONDITIONAL_WRITE);
        }
        if (!options.metadata().isEmpty()) {
            require(StorageCapability.USER_METADATA);
        }
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
            return out.finish();
        });
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        if (options.range().isPresent()) {
            require(StorageCapability.RANGE_READ);
        }
        return execute("get", key, true, () -> driver.get(key, options));
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        Objects.requireNonNull(key, "key");
        return execute("head", key, true, () -> driver.head(key));
    }

    @Override
    public void delete(BlobKey key) {
        Objects.requireNonNull(key, "key");
        execute("delete", key, true, () -> {
            driver.delete(key);
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
