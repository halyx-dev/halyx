package org.kamranzafar.cirrus.vault;

import org.kamranzafar.cirrus.json.Json;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.parameters.Parameter;
import org.kamranzafar.cirrus.parameters.ParameterCapability;
import org.kamranzafar.cirrus.parameters.spi.AbstractParameterDriver;

import javax.net.ssl.SSLHandshakeException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parameters and secrets on Vault's KV v2 engine. Each portable name is a secret path; the value is
 * stored in the field {@code value}. Secrets written by other tools without that field are read as
 * their JSON data object. Deletes remove all versions (the metadata).
 */
final class VaultKvDriver extends AbstractParameterDriver {

    static final String FIELD = "value";

    private final VaultClient client;
    private final String mount;

    VaultKvDriver(VaultClient client, String mount, boolean nativeNames) {
        super(VaultProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.client = client;
        this.mount = mount;
    }

    @Override
    public Optional<Parameter> get(String name) {
        return read(name, client.call("GET", mount + "/data/" + name, null));
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        return read(name, client.call("GET", mount + "/data/" + name + "?version=" + version, null));
    }

    private static Optional<Parameter> read(String name, Object response) {
        if (!(Json.at(response, "data", "data") instanceof Map<?, ?> data)) {
            return Optional.empty(); // missing, or a deleted/destroyed version
        }
        String value = data.get(FIELD) instanceof String v ? v : Json.write(data);
        Object version = Json.at(response, "data", "metadata", "version");
        Object created = Json.at(response, "data", "metadata", "created_time");
        return Optional.of(new Parameter(name, value, String.valueOf(version),
                created instanceof String c && !c.isEmpty() ? Instant.parse(c) : null));
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        Map<String, Parameter> found = new LinkedHashMap<>();
        if (nativeNames) {
            // A literal prefix may end mid-segment: list from its folder and filter.
            collect(prefix.substring(0, prefix.lastIndexOf('/') + 1), found);
            found.keySet().removeIf(name -> !name.startsWith(prefix));
            return found;
        }
        if (!prefix.isEmpty()) {
            get(prefix).ifPresent(p -> found.put(p.name(), p));
        }
        collect(prefix.isEmpty() ? "" : prefix + "/", found);
        return found;
    }

    private void collect(String folder, Map<String, Parameter> found) {
        if (!(Json.at(client.call("GET", mount + "/metadata/" + folder + "?list=true", null), "data", "keys") instanceof List<?> keys)) {
            return;
        }
        for (Object key : keys) {
            String entry = String.valueOf(key);
            if (entry.endsWith("/")) {
                collect(folder + entry, found);
            } else {
                String name = folder + entry;
                get(name).ifPresent(p -> found.put(name, p));
            }
        }
    }

    @Override
    public Parameter put(String name, String value) {
        Object response = client.call("POST", mount + "/data/" + name, Map.of("data", Map.of(FIELD, value)));
        return new Parameter(name, value, String.valueOf(Json.at(response, "data", "version")), null);
    }

    @Override
    public void delete(String name) {
        client.call("DELETE", mount + "/metadata/" + name, null);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        String provider = VaultProvider.ID;
        if (error instanceof VaultHttpException e) {
            String message = e.getMessage();
            return Optional.of(switch (e.status()) {
                case 401, 403 -> new AccessDeniedException(message, e, provider, String.valueOf(e.status()));
                case 404 -> new NotFoundException(message, e, provider, "404");
                case 429 -> new ThrottledException(message, e, provider, "429");
                case 400, 405 -> new InvalidArgumentException(message, e, provider, String.valueOf(e.status()));
                default -> new UnavailableException(message, e, provider, String.valueOf(e.status())); // 5xx, sealed
            });
        }
        if (error instanceof UncheckedIOException) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof SSLHandshakeException) {
                    return Optional.of(new AccessDeniedException("TLS handshake with Vault failed (" + t.getMessage()
                            + "); if Vault uses a private CA, set '" + VaultProperties.CA_CERTIFICATE + "'", error, provider, "TLS"));
                }
            }
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
