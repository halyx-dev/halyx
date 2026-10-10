package org.kamranzafar.stratum.parameters;

import java.time.Instant;
import java.util.Objects;

/**
 * A parameter or secret value.
 *
 * @param name         name relative to the store's prefix
 * @param version      opaque version id, usable with {@link ParameterStore#get(String, String)}
 * @param lastModified when this version was written, if the backend reports it
 */
public record Parameter(String name, String value, String version, Instant lastModified) {

    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(version, "version");
    }

    Parameter rename(String newName) {
        return new Parameter(newName, value, version, lastModified);
    }

    @Override
    public String toString() {
        return "Parameter[name=" + name + ", value=****, version=" + version + "]";
    }
}
