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
package dev.halyx.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRing;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.gcp.parameters.SecretManagerDriver;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.spi.ServerSideKey;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.tck.ParameterServerSideKeyConformanceTest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parameter store TCK on Secret Manager emulated by Floci-gcp (gRPC), without and with a customer-managed key from
 * Floci-gcp's Cloud KMS.
 */
@Testcontainers(disabledWithoutDocker = true)
class SecretManagerConformanceIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(4588)
            .waitingFor(Wait.forHttp("/health").forPort(4588));

    private static final String KEY_RING = "projects/floci-local/locations/global/keyRings/tck-secrets";

    private static Halyx halyx;
    private static CryptoKey secretsKey;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4588)))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "floci-local"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    @Nested
    class Secrets extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Test
    void secretIdsUseDoubleUnderscoreSeparators() {
        ParameterStore store = halyx.provider("gcp").parameterStore(ParameterStoreConfig.secrets().build());
        store.put("orders/db/password", "pw");

        var client = store.unwrap(SecretManagerServiceClient.class);
        assertThat(client.accessSecretVersion(SecretVersionName.of("floci-local", "orders__db__password", "latest"))
                .getPayload().getData().toStringUtf8()).isEqualTo("pw");
        store.delete("orders/db/password");
    }

    /** Crypto key secrets in a global key ring on Floci-gcp, made once. */
    private static synchronized CryptoKey secretsKey() {
        if (secretsKey == null) {
            KeyStore keys = halyx.provider("gcp").keyStore(KeyStoreConfig.builder().location(KEY_RING).build());
            KeyManagementServiceClient admin = keys.unwrap(KeyManagementServiceClient.class);
            admin.createKeyRing("projects/floci-local/locations/global", "tck-secrets", KeyRing.getDefaultInstance());
            admin.createCryptoKey(KEY_RING, "secrets", com.google.cloud.kms.v1.CryptoKey.newBuilder()
                    .setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
            secretsKey = keys.key("secrets");
        }
        return secretsKey;
    }

    @Nested
    class KeyedSecrets extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets().serverSideKey(secretsKey());
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    /** Floci-gcp accepts the key without recording it: {@link #newSecretsNameTheKey} checks the requests. */
    @Nested
    class ServerSideKeys extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return secretsKey();
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            var replication = store.unwrap(SecretManagerServiceClient.class)
                    .getSecret(SecretName.of("floci-local", name.replace("/", "__"))).getReplication();
            return replication.getAutomatic().hasCustomerManagedEncryption()
                    ? Optional.of(replication.getAutomatic().getCustomerManagedEncryption().getKmsKeyName())
                    : Optional.empty();
        }

        @Override
        protected boolean reportsServerSideKeys() {
            return false;
        }
    }

    @Test
    void newSecretsNameTheKey() throws Exception {
        List<CreateSecretRequest> creates = new CopyOnWriteArrayList<>();
        ClientInterceptor capture = new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void sendMessage(Q message) {
                        if (message instanceof CreateSecretRequest create) {
                            creates.add(create);
                        }
                        super.sendMessage(message);
                    }
                };
            }
        };
        ManagedChannel channel = ManagedChannelBuilder.forAddress(FLOCI.getHost(), FLOCI.getMappedPort(4588))
                .usePlaintext().intercept(capture).build();
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create(SecretManagerServiceSettings
                .newBuilder().setCredentialsProvider(NoCredentialsProvider.create())
                .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                .build())) {
            String keyName = secretsKey().metadata().nativeId();
            ParameterDriver plain = new SecretManagerDriver(client, "floci-local", false);
            ParameterDriver keyed = plain.withServerSideKey(new ServerSideKey(keyName, Map.of()));

            keyed.put("sse-check/a", "1");
            keyed.putAsync("sse-check/b", "2", Runnable::run).join();
            keyed.put("sse-check/a", "3"); // a new version of a secret that exists: no create
            plain.put("sse-check/c", "4");

            assertThat(creates).extracting(c -> c.getSecret().getReplication().getAutomatic()
                            .getCustomerManagedEncryption().getKmsKeyName())
                    .containsExactly(keyName, keyName, "");
            plain.delete("sse-check/a");
            plain.delete("sse-check/b");
            plain.delete("sse-check/c");
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void automaticReplicationNeedsAGlobalKey() {
        ParameterDriver driver = new SecretManagerDriver(halyx.provider("gcp").parameterStore(
                ParameterStoreConfig.secrets().build()).unwrap(SecretManagerServiceClient.class), "floci-local", false);

        assertThatThrownBy(() -> driver.withServerSideKey(new ServerSideKey(
                "projects/floci-local/locations/europe-west2/keyRings/r/cryptoKeys/k", Map.of())))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("'global'")
                .hasMessageContaining("europe-west2");
        assertThatThrownBy(() -> driver.withServerSideKey(new ServerSideKey("alias/not-gcp", Map.of())))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("not a Cloud KMS crypto key name");
    }
}
