package org.kamranzafar.stratum.capability;

import org.kamranzafar.stratum.error.UnsupportedFeatureException;

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
