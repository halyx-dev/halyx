package org.kamranzafar.stratum.gcp.parameters;

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
import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.gcp.GcpErrors;
import org.kamranzafar.stratum.gcp.GcpProvider;
import org.kamranzafar.stratum.parameters.Parameter;
import org.kamranzafar.stratum.parameters.ParameterCapability;
import org.kamranzafar.stratum.parameters.spi.AbstractParameterDriver;

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
