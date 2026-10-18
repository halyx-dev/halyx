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
package dev.halyx.gcp.parameters;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.DeleteSecretRequest;
import com.google.cloud.secretmanager.v1.ListSecretsRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.protobuf.ByteString;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpFutures;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.spi.AbstractParameterDriver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Secrets on Google Secret Manager. Secret ids allow letters, digits, {@code -} and {@code _}, so the
 * portable separator {@code /} is stored as {@code __} (portable names never contain underscores).
 * Each write adds a secret version; versions are the version numbers.
 */
public final class SecretManagerDriver extends AbstractParameterDriver {

    private final SecretManagerServiceClient client;
    private final String project;

    public SecretManagerDriver(SecretManagerServiceClient client, String project, boolean nativeNames) {
        super(GcpProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.client = client;
        this.project = project;
    }

    String encode(String name) {
        return nativeNames ? name : name.replace("/", "__");
    }

    String decode(String secretId) {
        return nativeNames ? secretId : secretId.replace("__", "/");
    }

    @Override
    public Optional<Parameter> get(String name) {
        return access(name, "latest");
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        return access(name, version);
    }

    private Optional<Parameter> access(String name, String version) {
        try {
            return Optional.of(toPortable(name, client.accessSecretVersion(SecretVersionName.of(project, encode(name), version))));
        } catch (ApiException e) {
            if (absent(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static Parameter toPortable(String name, AccessSecretVersionResponse response) {
        String resolved = SecretVersionName.parse(response.getName()).getSecretVersion();
        return new Parameter(name, response.getPayload().getData().toStringUtf8(), resolved, null);
    }

    /** Missing, or the version is disabled or destroyed. */
    private static boolean absent(Throwable error) {
        return GcpFutures.hasCode(error, StatusCode.Code.NOT_FOUND) || GcpFutures.hasCode(error, StatusCode.Code.FAILED_PRECONDITION);
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        List<String> names = new ArrayList<>();
        client.listSecrets(ProjectName.of(project)).iterateAll().forEach(secret -> {
            String name = decode(SecretName.parse(secret.getName()).getSecret());
            if (matches(name, prefix)) {
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
        SecretName secret = SecretName.of(project, encode(name));
        SecretPayload payload = payload(value);
        try {
            return toPortable(name, value, client.addSecretVersion(secret, payload).getName());
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                throw e;
            }
            client.createSecret(ProjectName.of(project), encode(name), newSecret());
            return toPortable(name, value, client.addSecretVersion(secret, payload).getName());
        }
    }

    private static SecretPayload payload(String value) {
        return SecretPayload.newBuilder().setData(ByteString.copyFrom(value, StandardCharsets.UTF_8)).build();
    }

    private static Secret newSecret() {
        return Secret.newBuilder()
                .setReplication(Replication.newBuilder().setAutomatic(Replication.Automatic.getDefaultInstance()))
                .build();
    }

    private static Parameter toPortable(String name, String value, String versionName) {
        return new Parameter(name, value, SecretVersionName.parse(versionName).getSecretVersion(), null);
    }

    @Override
    public void delete(String name) {
        try {
            client.deleteSecret(SecretName.of(project, encode(name)));
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, Executor blocking) {
        return accessAsync(name, "latest");
    }

    @Override
    public CompletableFuture<Optional<Parameter>> getAsync(String name, String version, Executor blocking) {
        return accessAsync(name, version);
    }

    private CompletableFuture<Optional<Parameter>> accessAsync(String name, String version) {
        AccessSecretVersionRequest request = AccessSecretVersionRequest.newBuilder()
                .setName(SecretVersionName.of(project, encode(name), version).toString()).build();
        return GcpFutures.of(client.accessSecretVersionCallable().futureCall(request))
                .thenApply(response -> Optional.of(toPortable(name, response)))
                .exceptionallyCompose(error -> absent(error) ? CompletableFuture.completedFuture(Optional.empty())
                        : CompletableFuture.failedFuture(error));
    }

    @Override
    public CompletableFuture<Map<String, Parameter>> listAsync(String prefix, Executor blocking) {
        ListSecretsRequest request = ListSecretsRequest.newBuilder().setParent(ProjectName.of(project).toString()).build();
        return GcpFutures.of(client.listSecretsPagedCallable().futureCall(request))
                .thenCompose(response -> GcpFutures.allPages(response.getPage()))
                .thenCompose(secrets -> {
                    List<CompletableFuture<Optional<Parameter>>> reads = secrets.stream()
                            .map(secret -> decode(SecretName.parse(secret.getName()).getSecret()))
                            .filter(name -> matches(name, prefix))
                            .map(name -> accessAsync(name, "latest")).toList();
                    return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(all -> {
                        Map<String, Parameter> found = new LinkedHashMap<>();
                        reads.forEach(read -> read.join().ifPresent(p -> found.put(p.name(), p)));
                        return found;
                    });
                });
    }

    @Override
    public CompletableFuture<Parameter> putAsync(String name, String value, Executor blocking) {
        AddSecretVersionRequest add = AddSecretVersionRequest.newBuilder()
                .setParent(SecretName.of(project, encode(name)).toString()).setPayload(payload(value)).build();
        return GcpFutures.of(client.addSecretVersionCallable().futureCall(add))
                .exceptionallyCompose(error -> {
                    if (!GcpFutures.hasCode(error, StatusCode.Code.NOT_FOUND)) {
                        return CompletableFuture.failedFuture(error);
                    }
                    CreateSecretRequest create = CreateSecretRequest.newBuilder().setParent(ProjectName.of(project).toString())
                            .setSecretId(encode(name)).setSecret(newSecret()).build();
                    return GcpFutures.of(client.createSecretCallable().futureCall(create))
                            .thenCompose(created -> GcpFutures.of(client.addSecretVersionCallable().futureCall(add)));
                })
                .thenApply(version -> toPortable(name, value, version.getName()));
    }

    @Override
    public CompletableFuture<Void> deleteAsync(String name, Executor blocking) {
        DeleteSecretRequest request = DeleteSecretRequest.newBuilder()
                .setName(SecretName.of(project, encode(name)).toString()).build();
        return GcpFutures.of(client.deleteSecretCallable().futureCall(request)).<Void>thenApply(empty -> null)
                .exceptionallyCompose(error -> GcpFutures.hasCode(error, StatusCode.Code.NOT_FOUND)
                        ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(error));
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
