package org.kamranzafar.cirrus.azure.parameters;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import org.kamranzafar.cirrus.azure.AzureErrors;
import org.kamranzafar.cirrus.azure.AzureProvider;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.parameters.Parameter;
import org.kamranzafar.cirrus.parameters.ParameterCapability;
import org.kamranzafar.cirrus.parameters.spi.AbstractParameterDriver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Secrets on Azure Key Vault. Key Vault names allow only letters, digits and dashes, so the portable
 * separator {@code /} is stored as {@code --} ({@code orders/db/password} becomes
 * {@code orders--db--password}); portable names never contain {@code --}, so this is reversible.
 *
 * <p>Deletes are soft deletes (Key Vault's recovery protection stays intact). Writing a name that is
 * soft-deleted recovers it first, then stores the new value as a new version.
 */
public final class KeyVaultSecretDriver extends AbstractParameterDriver {

    private static final Duration POLL = Duration.ofMillis(200);

    private final SecretClient secrets;

    public KeyVaultSecretDriver(SecretClient secrets, boolean nativeNames) {
        super(AzureProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.secrets = secrets;
    }

    String encode(String name) {
        return nativeNames ? name : name.replace("/", "--");
    }

    String decode(String vaultName) {
        return nativeNames ? vaultName : vaultName.replace("--", "/");
    }

    @Override
    public Optional<Parameter> get(String name) {
        try {
            return Optional.of(toPortable(name, secrets.getSecret(encode(name))));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        try {
            return Optional.of(toPortable(name, secrets.getSecret(encode(name), version)));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        List<String> names = new ArrayList<>();
        secrets.listPropertiesOfSecrets().forEach(properties -> {
            String name = decode(properties.getName());
            if (Boolean.TRUE.equals(properties.isEnabled()) && matches(name, prefix)) {
                names.add(name);
            }
        });
        Map<String, Parameter> found = new LinkedHashMap<>();
        for (String name : names) {
            get(name).ifPresent(p -> found.put(name, p));
        }
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        try {
            return toPortable(name, secrets.setSecret(encode(name), value));
        } catch (HttpResponseException e) {
            if (e.getResponse() == null || e.getResponse().getStatusCode() != 409) {
                throw e;
            }
            // The name is soft-deleted: recover it, then write the new version.
            secrets.beginRecoverDeletedSecret(encode(name)).setPollInterval(POLL).waitForCompletion();
            return toPortable(name, secrets.setSecret(encode(name), value));
        }
    }

    @Override
    public void delete(String name) {
        try {
            secrets.beginDeleteSecret(encode(name)).setPollInterval(POLL).waitForCompletion();
        } catch (ResourceNotFoundException ignored) {
            // already gone
        }
    }

    private static Parameter toPortable(String name, KeyVaultSecret secret) {
        var updated = secret.getProperties().getUpdatedOn();
        return new Parameter(name, secret.getValue(), secret.getProperties().getVersion(),
                updated == null ? null : updated.toInstant());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AzureErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(secrets);
    }
}
