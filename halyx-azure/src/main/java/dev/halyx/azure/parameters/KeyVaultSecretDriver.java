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
package dev.halyx.azure.parameters;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretAsyncClient;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import dev.halyx.azure.AzureErrors;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnavailableException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.spi.AbstractParameterDriver;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
 *
 * <p>Asynchronous calls are non-blocking, except deletes and the recovery a conflicting write starts: the SDK
 * starts long-running operations only through a poller, so those run the blocking calls on the blocking executor.
 */
public final class KeyVaultSecretDriver extends AbstractParameterDriver {

    /** Secrets read at once while listing. */
    private static final int LIST_CONCURRENCY = 8;

    private final SecretClient secrets;
    private final SecretAsyncClient asyncSecrets;

    public KeyVaultSecretDriver(SecretClient secrets, SecretAsyncClient asyncSecrets, boolean nativeNames) {
        super(AzureProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.secrets = secrets;
        this.asyncSecrets = asyncSecrets;
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

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, Executor blocking) {
        return found(name, asyncSecrets.getSecret(encode(name))).toFuture();
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, String version, Executor blocking) {
        return found(name, asyncSecrets.getSecret(encode(name), version)).toFuture();
    }

    /** The secret, or empty if Key Vault has none. */
    private static Mono<Optional<Parameter>> found(String name, Mono<KeyVaultSecret> read) {
        return read.map(secret -> Optional.of(toPortable(name, secret)))
                .onErrorResume(ResourceNotFoundException.class, e -> Mono.just(Optional.empty()));
    }

    @Override
    public CompletableFuture<Map<String, Parameter>> listAsync(String prefix, Executor blocking) {
        return asyncSecrets.listPropertiesOfSecrets()
                .filter(properties -> Boolean.TRUE.equals(properties.isEnabled()) && matches(decode(properties.getName()), prefix))
                .map(properties -> decode(properties.getName()))
                .flatMapSequential(name -> found(name, asyncSecrets.getSecret(encode(name))), LIST_CONCURRENCY)
                .collect(LinkedHashMap<String, Parameter>::new, (found, p) -> p.ifPresent(parameter -> found.put(parameter.name(), parameter)))
                .<Map<String, Parameter>>map(found -> found).toFuture();
    }

    /** A write that conflicts with a deleted secret starts its recovery, which needs the blocking client. */
    @Override
    public CompletableFuture<Parameter> putAsync(String name, String value, Executor blocking) {
        return asyncSecrets.setSecret(encode(name), value).toFuture().thenApply(secret -> toPortable(name, secret))
                .exceptionallyCompose(error -> AsyncInvocation.unwrap(error) instanceof HttpResponseException e && conflict(e)
                        ? CompletableFuture.supplyAsync(() -> put(name, value), blocking)
                        : CompletableFuture.failedFuture(error));
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
        return List.of(secrets, asyncSecrets);
    }
}
