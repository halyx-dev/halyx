package org.kamranzafar.stratum.parameters;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.NotFoundException;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Portable parameters and secrets: SSM Parameter Store / Secrets Manager, App Configuration /
 * Key Vault, Parameter Manager / Secret Manager, and HashiCorp Vault. Names are relative to the
 * store's prefix and follow {@link ParameterName}. Thread-safe.
 */
public interface ParameterStore {

    String provider();

    ParameterKind kind();

    /** The current value, or empty. */
    Optional<Parameter> get(String name);

    /** A specific version. Requires {@link ParameterCapability#VERSIONS}. */
    Optional<Parameter> get(String name, String version);

    /** The current value, failing if absent. */
    default String require(String name) {
        return get(name).map(Parameter::value)
                .orElseThrow(() -> new NotFoundException("No " + kind().name().toLowerCase() + " named '" + name + "'"));
    }

    /** The current value converted, such as {@code get("pool/size", Integer::valueOf)}. */
    default <T> Optional<T> get(String name, Function<String, T> converter) {
        return get(name).map(Parameter::value).map(converter);
    }

    /** Every value at or below {@code prefix} (empty for all), keyed by name. */
    Map<String, Parameter> getAll(String prefix);

    /** Creates or replaces a value and returns the stored version. */
    Parameter put(String name, String value);

    /** Deletes a value with all its versions. Idempotent. */
    void delete(String name);

    /** Drops cached reads, so the next read goes to the backend. */
    void refresh();

    Capabilities capabilities();

    <T> T unwrap(Class<T> nativeType);
}
