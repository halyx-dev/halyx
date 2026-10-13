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
package dev.halyx.stream.spi;

import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Template Method base for stream producer drivers: error translation fallback and {@code unwrap} lookup. */
public abstract class AbstractProducerDriver implements ProducerDriver {

    protected final String provider;

    protected AbstractProducerDriver(String provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    protected abstract Optional<CloudException> translateNative(Throwable error);

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
                "Unexpected stream error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        for (Object client : nativeClients()) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native stream producer client of type "
                + nativeType.getName());
    }
}
