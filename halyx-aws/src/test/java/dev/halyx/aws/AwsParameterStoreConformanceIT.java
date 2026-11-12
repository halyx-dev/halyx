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
package dev.halyx.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.aws.parameters.SsmParameterDriver;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.spi.ServerSideKey;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.tck.ParameterServerSideKeyConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parameter store TCK on SSM Parameter Store and Secrets Manager emulated by Floci, without and with a server-side
 * key from Floci's KMS.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsParameterStoreConformanceIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;
    private static CryptoKey parametersKey;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("aws", ProviderConfig.of("aws")
                        .region("us-east-1")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566)))
                        .credentials(Credentials.of("test", "test")))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    @Nested
    class SsmParameterStore extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return "/" + unique + "/";
        }
    }

    @Nested
    class SecretsManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }
    }

    @Test
    void secureParametersAreWrittenAsSecureString() {
        var store = halyx.provider("aws").parameterStore(ParameterStoreConfig.parameters()
                .prefix("secure-check").property(SsmParameterDriver.SECURE, "true").build());
        store.put("token", "s3cr3t");

        var native_ = store.unwrap(SsmClient.class).getParameter(b -> b.name("/secure-check/token")).parameter();
        assertThat(native_.type()).isEqualTo(ParameterType.SECURE_STRING);
        assertThat(store.require("token")).isEqualTo("s3cr3t");
        store.delete("token");
    }

    /** A key on Floci's KMS, made once, alias/parameters. */
    private static synchronized CryptoKey parametersKey() {
        if (parametersKey == null) {
            KeyStore keys = halyx.provider("aws").keyStore();
            KmsClient admin = keys.unwrap(KmsClient.class);
            String id = admin.createKey(b -> b.keySpec(KeySpec.SYMMETRIC_DEFAULT).keyUsage(KeyUsageType.ENCRYPT_DECRYPT))
                    .keyMetadata().keyId();
            admin.createAlias(b -> b.aliasName("alias/parameters").targetKeyId(id));
            parametersKey = keys.key("parameters");
        }
        return parametersKey;
    }

    private static ParameterStoreConfig.Builder secureParameters() {
        return ParameterStoreConfig.parameters().property(SsmParameterDriver.SECURE, "true");
    }

    @Nested
    class KeyedSsmParameterStore extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return secureParameters().serverSideKey(parametersKey());
        }

        @Override
        protected String nativePrefix(String unique) {
            return "/" + unique + "/";
        }
    }

    @Nested
    class KeyedSecretsManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets().serverSideKey(parametersKey());
        }
    }

    /** Floci accepts SSM's {@code KeyId} without recording it: {@link #ssmPutsNameTheKey} checks the requests. */
    @Nested
    class SsmServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return secureParameters();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return parametersKey();
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return store.unwrap(SsmClient.class).describeParameters(b -> b.parameterFilters(
                            f -> f.key("Name").values("/" + name))).parameters().stream()
                    .findFirst().map(p -> p.keyId());
        }

        @Override
        protected boolean reportsServerSideKeys() {
            return false;
        }

        @Override
        protected boolean everyPutUsesTheKey() {
            return true;
        }
    }

    @Nested
    class SecretsManagerServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return parametersKey();
        }

        /** DescribeSecret's {@code KmsKeyId}: none for the account's default key. */
        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return Optional.ofNullable(store.unwrap(SecretsManagerClient.class)
                    .describeSecret(b -> b.secretId(name)).kmsKeyId());
        }
    }

    @Test
    void ssmPutsNameTheKey() {
        List<PutParameterRequest> puts = new CopyOnWriteArrayList<>();
        ExecutionInterceptor capture = new ExecutionInterceptor() {
            @Override
            public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
                if (context.request() instanceof PutParameterRequest put) {
                    puts.add(put);
                }
            }
        };
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).overrideConfiguration(o -> o.addExecutionInterceptor(capture)).build();
             SsmAsyncClient ssmAsync = SsmAsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).overrideConfiguration(o -> o.addExecutionInterceptor(capture))
                     .build()) {
            String arn = parametersKey().metadata().nativeId();
            ParameterDriver keyed = new SsmParameterDriver(ssm, true, false, () -> ssmAsync)
                    .withServerSideKey(new ServerSideKey(arn, Map.of()));

            keyed.put("sse-check/a", "1");
            keyed.putAsync("sse-check/b", "2", Runnable::run).join();
            new SsmParameterDriver(ssm, true, false, () -> ssmAsync).put("sse-check/c", "3");

            assertThat(puts).extracting(PutParameterRequest::keyId).containsExactly(arn, arn, null);
            assertThat(puts).extracting(PutParameterRequest::type).containsOnly(ParameterType.SECURE_STRING);
            keyed.delete("sse-check/a");
            keyed.delete("sse-check/b");
            keyed.delete("sse-check/c");
        }
    }

    @Test
    void aKeyNeedsSecureSsmParameters() {
        assertThatThrownBy(() -> halyx.provider("aws").parameterStore(ParameterStoreConfig.parameters()
                .serverSideKey(parametersKey()).build()))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining(SsmParameterDriver.SECURE);
    }
}
