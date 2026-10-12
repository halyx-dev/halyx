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
package org.kamranzafar.halyx.aws.parameters;

import org.kamranzafar.halyx.aws.AwsErrors;
import org.kamranzafar.halyx.aws.AwsProvider;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.UnavailableException;
import org.kamranzafar.halyx.parameters.Parameter;
import org.kamranzafar.halyx.parameters.ParameterCapability;
import org.kamranzafar.halyx.parameters.spi.AbstractParameterDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.FilterNameStringType;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException;

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

    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerDriver.class);

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
        } catch (InvalidRequestException e) {
            if (!pendingDeletion(e)) {
                throw e;
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        try {
            return Optional.of(toPortable(name, secrets.getSecretValue(b -> b.secretId(name).versionId(version))));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        } catch (InvalidRequestException e) {
            if (!pendingDeletion(e)) {
                throw e;
            }
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
            if (batchReads) {
                try {
                    // secrets deleted since they were listed come back in errors() and are left out
                    secrets.batchGetSecretValue(b -> b.secretIdList(batch)).secretValues().forEach(v ->
                            found.put(v.name(), new Parameter(v.name(), v.secretString(), v.versionId(), v.createdDate())));
                    continue;
                } catch (SecretsManagerException e) {
                    if (!accessDenied(e)) {
                        throw e;
                    }
                    batchReads = false;
                    LOG.warn("secretsmanager:BatchGetSecretValue is denied; listing reads secrets one by one");
                }
            }
            batch.forEach(name -> get(name).ifPresent(p -> found.put(name, p)));
        }
        return found;
    }

    /**
     * BatchGetSecretValue needs its own permission on {@code "*"}. A policy that grants only GetSecretValue
     * still works: the first denial switches listing to one read per secret.
     */
    private volatile boolean batchReads = true;

    private static boolean accessDenied(SecretsManagerException e) {
        return e.awsErrorDetails() != null && "AccessDeniedException".equals(e.awsErrorDetails().errorCode());
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

    /**
     * Even a forced delete finishes asynchronously. For a few seconds reads may still return the secret,
     * and writing the name fails with {@link UnavailableException} (retryable) because it is "marked for
     * deletion". Nothing here waits for that: callers that reuse a name right away retry.
     */
    @Override
    public void delete(String name) {
        try {
            secrets.deleteSecret(b -> b.secretId(name).forceDeleteWithoutRecovery(true));
        } catch (ResourceNotFoundException ignored) {
            // already gone
        } catch (InvalidRequestException e) {
            if (!pendingDeletion(e)) {
                throw e;
            }
            // already being deleted
        }
    }

    /** Secrets Manager says so in the message only: "...because it was marked for deletion" / "scheduled for deletion". */
    private static boolean pendingDeletion(SecretsManagerException e) {
        String message = e.awsErrorDetails() == null ? e.getMessage() : e.awsErrorDetails().errorMessage();
        return message != null && (message.contains("marked for deletion") || message.contains("scheduled for deletion"));
    }

    private static Parameter toPortable(String name, GetSecretValueResponse r) {
        return new Parameter(name, r.secretString(), r.versionId(), r.createdDate());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        if (error instanceof SecretsManagerException e && pendingDeletion(e)) {
            return Optional.of(new UnavailableException("Secret is still being deleted; retry shortly: " + e.getMessage(),
                    e, AwsProvider.ID, e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode()));
        }
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(secrets);
    }
}
