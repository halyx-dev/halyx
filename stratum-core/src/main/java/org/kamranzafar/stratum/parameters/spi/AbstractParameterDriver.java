package org.kamranzafar.stratum.parameters.spi;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.UnknownCloudException;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;
import org.kamranzafar.stratum.parameters.Parameter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Template Method base for parameter drivers. */
public abstract class AbstractParameterDriver implements ParameterDriver {

    protected final String provider;
    private final Capabilities capabilities;

    protected AbstractParameterDriver(String provider, Capabilities capabilities) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
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
