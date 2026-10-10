package org.kamranzafar.cirrus.cache.spi;

import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.UnknownCloudException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Template Method base for cache drivers: error translation fallback and {@code unwrap} lookup. */
public abstract class AbstractCacheDriver implements CacheDriver {

    protected final String provider;

    protected AbstractCacheDriver(String provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    protected abstract Optional<CloudException> translateNative(Throwable error);

    protected abstract List<Object> nativeClients();

    @Override
    public final CloudException translate(Throwable error) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        return translateNative(error).orElseGet(() -> new UnknownCloudException(
                "Unexpected cache error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        for (Object client : nativeClients()) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native cache client of type "
                + nativeType.getName());
    }
}
