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
package org.kamranzafar.halyx.capability;

import org.kamranzafar.halyx.error.UnsupportedFeatureException;

import java.util.Set;

/**
 * The set of optional features a client supports. This lets callers use provider-specific strengths
 * without falling back to the lowest common denominator:
 *
 * <pre>{@code
 * if (store.capabilities().supports(StorageCapability.PRESIGNED_URL)) { ... }
 * }</pre>
 */
public final class Capabilities {

    private static final Capabilities NONE = new Capabilities(Set.of());

    private final Set<Capability> supported;

    private Capabilities(Set<Capability> supported) {
        this.supported = Set.copyOf(supported);
    }

    public static Capabilities of(Capability... capabilities) {
        return new Capabilities(Set.of(capabilities));
    }

    public static Capabilities none() {
        return NONE;
    }

    public boolean supports(Capability capability) {
        return supported.contains(capability);
    }

    /** Throws {@link UnsupportedFeatureException} unless {@code capability} is supported. */
    public void require(Capability capability, String provider) {
        if (!supports(capability)) {
            throw new UnsupportedFeatureException(
                    "Provider '" + provider + "' does not support " + capability.name(), null, provider, null);
        }
    }

    public Set<Capability> asSet() {
        return supported;
    }

    @Override
    public String toString() {
        return "Capabilities" + supported;
    }
}
