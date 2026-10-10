package org.kamranzafar.cirrus.messaging.spi;

import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.UnknownCloudException;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Shared plumbing for messaging drivers: error translation and native client lookup. */
public final class MessagingErrors {

    private MessagingErrors() {
    }

    public static CloudException translate(Throwable error, String provider,
                                           Function<Throwable, Optional<CloudException>> nativeTranslation) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        return nativeTranslation.apply(error).orElseGet(() -> new UnknownCloudException(
                "Unexpected error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    public static <T> T unwrap(Class<T> nativeType, String provider, List<Object> clients) {
        for (Object client : clients) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native client of type " + nativeType.getName());
    }
}
