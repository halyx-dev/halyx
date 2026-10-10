package org.kamranzafar.stratum.storage.spi;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.UnknownCloudException;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.PresignRequest;
import org.kamranzafar.stratum.storage.StorageTier;
import org.kamranzafar.stratum.storage.PresignedUrl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Template Method base for blob drivers. Subclasses implement the storage operations, list their
 * native clients for {@link #unwrap}, and map native errors in {@link #translateNative}. Everything
 * else (pass-through of already-portable errors, the unknown-error fallback, unwrap lookup) is
 * handled here.
 */
public abstract class AbstractBlobStoreDriver implements BlobStoreDriver {

    protected final String provider;
    protected final String bucket;
    private final Capabilities capabilities;

    protected AbstractBlobStoreDriver(String provider, String bucket, Capabilities capabilities) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** Maps a native SDK exception, or returns empty if it is not recognised. */
    protected abstract Optional<CloudException> translateNative(Throwable error);

    /** Native SDK clients exposed through {@link #unwrap}. */
    protected abstract List<Object> nativeClients();

    @Override
    public final CloudException translate(Throwable error) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        return translateNative(error).orElseGet(() -> new UnknownCloudException(
                "Unexpected error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        throw new UnsupportedFeatureException("presign is not supported by " + provider, null, provider, null);
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        throw new UnsupportedFeatureException("Storage tiers are not supported by " + provider, null, provider, null);
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        for (Object client : nativeClients()) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native client of type " + nativeType.getName());
    }
}
