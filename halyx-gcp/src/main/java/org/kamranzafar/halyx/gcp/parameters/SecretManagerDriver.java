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
package org.kamranzafar.halyx.gcp.parameters;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.protobuf.ByteString;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.gcp.GcpErrors;
import org.kamranzafar.halyx.gcp.GcpProvider;
import org.kamranzafar.halyx.parameters.Parameter;
import org.kamranzafar.halyx.parameters.ParameterCapability;
import org.kamranzafar.halyx.parameters.spi.AbstractParameterDriver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            AccessSecretVersionResponse response =
                    client.accessSecretVersion(SecretVersionName.of(project, encode(name), version));
            String resolved = SecretVersionName.parse(response.getName()).getSecretVersion();
            return Optional.of(new Parameter(name, response.getPayload().getData().toStringUtf8(), resolved, null));
        } catch (ApiException e) {
            StatusCode.Code code = e.getStatusCode().getCode();
            if (code == StatusCode.Code.NOT_FOUND || code == StatusCode.Code.FAILED_PRECONDITION) {
                return Optional.empty(); // missing, or the version is disabled or destroyed
            }
            throw e;
        }
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
        SecretPayload payload = SecretPayload.newBuilder().setData(ByteString.copyFrom(value, StandardCharsets.UTF_8)).build();
        try {
            return toPortable(name, value, client.addSecretVersion(secret, payload).getName());
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                throw e;
            }
            client.createSecret(ProjectName.of(project), encode(name), Secret.newBuilder()
                    .setReplication(Replication.newBuilder().setAutomatic(Replication.Automatic.getDefaultInstance()))
                    .build());
            return toPortable(name, value, client.addSecretVersion(secret, payload).getName());
        }
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
    protected Optional<CloudException> translateNative(Throwable error) {
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
