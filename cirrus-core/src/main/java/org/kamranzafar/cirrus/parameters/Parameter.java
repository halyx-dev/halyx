package org.kamranzafar.cirrus.parameters;

import org.kamranzafar.cirrus.json.Json;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    /** The value parsed as a JSON object, if it is one (such as an RDS-managed or multi-field Vault secret). */
    public Optional<Map<String, Object>> json() {
        return Json.parseObject(value);
    }

    /**
     * A field of a JSON object value, by dotted path ({@code password}, {@code db.host}, {@code hosts[0]}),
     * or empty if the value is not JSON or has no such field.
     */
    public Optional<String> field(String path) {
        return Optional.ofNullable(fields().get(path));
    }

    /** All fields of a JSON object value flattened to dotted paths; empty if the value is not a JSON object. */
    public Map<String, String> fields() {
        return json().map(Json::flatten).orElse(Map.of());
    }

    Parameter rename(String newName) {
        return new Parameter(newName, value, version, lastModified);
    }

    @Override
    public String toString() {
        return "Parameter[name=" + name + ", value=****, version=" + version + "]";
    }
}
