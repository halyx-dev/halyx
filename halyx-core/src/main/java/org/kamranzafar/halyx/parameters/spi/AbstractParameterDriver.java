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
package org.kamranzafar.halyx.parameters.spi;

import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.UnknownCloudException;
import org.kamranzafar.halyx.error.UnsupportedFeatureException;
import org.kamranzafar.halyx.parameters.Parameter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Template Method base for parameter drivers. */
public abstract class AbstractParameterDriver implements ParameterDriver {

    protected final String provider;
    /** Names are the backend's own: no separator translation, and prefixes match literally. */
    protected final boolean nativeNames;
    private final Capabilities capabilities;

    protected AbstractParameterDriver(String provider, Capabilities capabilities, boolean nativeNames) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.nativeNames = nativeNames;
    }

    /** Whether {@code name} belongs to {@code prefix}: by segment for portable names, literally for native ones. */
    protected boolean matches(String name, String prefix) {
        return nativeNames ? name.startsWith(prefix) : under(name, prefix);
    }

    protected abstract Optional<CloudException> translateNative(Throwable error);

    protected abstract List<Object> nativeClients();

    /** True if {@code name} is {@code prefix} or below it. */
    protected static boolean under(String name, String prefix) {
        return prefix.isEmpty() || name.equals(prefix) || name.startsWith(prefix + "/");
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        throw new UnsupportedFeatureException("Versions are not supported by " + provider, null, provider, null);
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public final CloudException translate(Throwable error) {
        if (error instanceof CloudException portable) {
            return portable;
        }
        return translateNative(error).orElseGet(() -> new UnknownCloudException(
                "Unexpected parameter store error from provider '" + provider + "': " + error.getMessage(), error, provider, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        for (Object client : nativeClients()) {
            if (nativeType.isInstance(client)) {
                return nativeType.cast(client);
            }
        }
        throw new IllegalArgumentException("Provider '" + provider + "' has no native parameter client of type "
                + nativeType.getName());
    }
}
