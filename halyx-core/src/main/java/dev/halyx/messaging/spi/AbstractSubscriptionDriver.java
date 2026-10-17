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
package dev.halyx.messaging.spi;

import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Template Method base for messaging subscription drivers. Subclasses map native errors in {@link #translateNative} and list
 * their native clients for {@link #unwrap}; the pass-through of already-portable errors, the unknown-error fallback
 * and the {@code unwrap} lookup are handled here, as for every other driver.
 */
public abstract class AbstractSubscriptionDriver implements SubscriptionDriver {

    protected final String provider;

    protected AbstractSubscriptionDriver(String provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /** Maps a native SDK exception, or returns empty if it is not recognised. */
    protected abstract Optional<CloudException> translateNative(Throwable error);

    /** Native SDK clients exposed through {@link #unwrap}; empty when there are none. */
    protected abstract List<Object> nativeClients();

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public final CloudException translate(Throwable error) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        return translateNative(error).orElseGet(() -> new UnknownCloudException(
                "Unexpected messaging error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        for (Object client : nativeClients()) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native messaging client of type "
                + nativeType.getName());
    }
}
