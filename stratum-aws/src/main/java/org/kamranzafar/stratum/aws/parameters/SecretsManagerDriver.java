package org.kamranzafar.stratum.aws.parameters;

import org.kamranzafar.stratum.aws.AwsErrors;
import org.kamranzafar.stratum.aws.AwsProvider;
import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.parameters.Parameter;
import org.kamranzafar.stratum.parameters.ParameterCapability;
import org.kamranzafar.stratum.parameters.spi.AbstractParameterDriver;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.FilterNameStringType;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Secrets on AWS Secrets Manager. Portable names are used as secret names unchanged; values are
 * string secrets. Deletes skip the recovery window so names can be reused immediately.
 */
public final class SecretsManagerDriver extends AbstractParameterDriver {

    /** BatchGetSecretValue accepts up to 20 secret ids. */
    private static final int BATCH = 20;

    private final SecretsManagerClient secrets;

    public SecretsManagerDriver(SecretsManagerClient secrets, boolean nativeNames) {
        super(AwsProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.secrets = secrets;
    }

    @Override
    public Optional<Parameter> get(String name) {
        try {
            return Optional.of(toPortable(name, secrets.getSecretValue(b -> b.secretId(name))));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        try {
            return Optional.of(toPortable(name, secrets.getSecretValue(b -> b.secretId(name).versionId(version))));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        List<String> names = new ArrayList<>();
        secrets.listSecretsPaginator(b -> {
            if (!prefix.isEmpty()) {
                b.filters(f -> f.key(FilterNameStringType.NAME).values(prefix));
            }
        }).stream().flatMap(page -> page.secretList().stream()).forEach(entry -> {
            if (matches(entry.name(), prefix)) {
                names.add(entry.name());
            }
        });
        Map<String, Parameter> found = new LinkedHashMap<>();
        for (int from = 0; from < names.size(); from += BATCH) {
            List<String> batch = names.subList(from, Math.min(from + BATCH, names.size()));
            secrets.batchGetSecretValue(b -> b.secretIdList(batch)).secretValues().forEach(v ->
                    found.put(v.name(), new Parameter(v.name(), v.secretString(), v.versionId(), v.createdDate())));
        }
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        try {
            var response = secrets.putSecretValue(b -> b.secretId(name).secretString(value));
            return new Parameter(name, value, response.versionId(), null);
        } catch (ResourceNotFoundException e) {
            var response = secrets.createSecret(b -> b.name(name).secretString(value));
            return new Parameter(name, value, response.versionId(), null);
        }
    }

    @Override
    public void delete(String name) {
        try {
            secrets.deleteSecret(b -> b.secretId(name).forceDeleteWithoutRecovery(true));
        } catch (ResourceNotFoundException ignored) {
            // already gone
        }
    }

    private static Parameter toPortable(String name, GetSecretValueResponse r) {
        return new Parameter(name, r.secretString(), r.versionId(), r.createdDate());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(secrets);
    }
}
