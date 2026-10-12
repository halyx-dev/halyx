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
package org.kamranzafar.halyx.azure.parameters;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import org.kamranzafar.halyx.azure.AzureErrors;
import org.kamranzafar.halyx.azure.AzureProvider;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.UnavailableException;
import org.kamranzafar.halyx.parameters.Parameter;
import org.kamranzafar.halyx.parameters.ParameterCapability;
import org.kamranzafar.halyx.parameters.spi.AbstractParameterDriver;

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
 * <p>Deletes are soft deletes (Key Vault's recovery protection stays intact) and finish asynchronously:
 * {@code delete} starts one and returns. Writing a name that is soft-deleted, or still being deleted or
 * recovered, fails with {@link UnavailableException} (retryable); if the name was soft-deleted, the write
 * also starts its recovery, so a retry once that finishes stores the new value as a new version. Nothing
 * here waits for Key Vault's long-running operations: callers that reuse names right away retry.
 */
public final class KeyVaultSecretDriver extends AbstractParameterDriver {

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
            if (!conflict(e)) {
                throw e;
            }
            try {
                secrets.beginRecoverDeletedSecret(encode(name)); // starts recovery; doesn't wait for it
            } catch (HttpResponseException notRecoverableYet) {
                // still being deleted, or already being recovered
            }
            throw new UnavailableException("Key Vault secret '" + name + "' is deleted, or being deleted or recovered; "
                    + "it is being recovered, so retry the write shortly", e, AzureProvider.ID, "Conflict");
        }
    }

    @Override
    public void delete(String name) {
        try {
            secrets.beginDeleteSecret(encode(name)); // starts the soft delete; doesn't wait for it
        } catch (ResourceNotFoundException ignored) {
            // already gone
        } catch (HttpResponseException e) {
            if (!conflict(e)) {
                throw e;
            }
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (!message.contains("being deleted")) {
                throw new UnavailableException("Key Vault secret '" + name + "' is being recovered; retry the delete shortly",
                        e, AzureProvider.ID, "Conflict");
            }
            // already being deleted
        }
    }

    private static boolean conflict(HttpResponseException e) {
        return e.getResponse() != null && e.getResponse().getStatusCode() == 409;
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
