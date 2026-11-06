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
package dev.halyx.spring.keys;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.kms.v1.KeyRing;
import dev.halyx.error.NotFoundException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.reactor.ReactiveCryptoKey;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Key stores and keys from properties on Cloud KMS emulated by Floci-gcp, on Boot 3.5 and 4. */
@Testcontainers(disabledWithoutDocker = true)
class HalyxGcpKeysIT {

    private static final String PROJECT = "floci-local";
    private static final String KEY_RING = "projects/" + PROJECT + "/locations/global/keyRings/orders";
    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(4588)
            .waitingFor(Wait.forListeningPort());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
            .withPropertyValues(
                    "halyx.providers.gcp.type=gcp",
                    "halyx.providers.gcp.endpoint=http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4588),
                    "halyx.providers.gcp.credentials.type=anonymous",
                    "halyx.providers.gcp.properties[gcp.project-id]=" + PROJECT,
                    "halyx.key-stores.kms.location=" + KEY_RING,
                    "halyx.key-stores.kms.prefix=orders-",
                    "halyx.keys.data.check-on-startup=true",
                    "halyx.keys.tokens.name=signing",
                    "halyx.keys.tokens.signing-algorithm=ecdsa-p256-sha256");

    @BeforeAll
    static void keys() throws Exception {
        var channel = ManagedChannelBuilder.forAddress(FLOCI.getHost(), FLOCI.getMappedPort(4588)).usePlaintext()
                .build();
        try (KeyManagementServiceClient admin = KeyManagementServiceClient.create(KeyManagementServiceSettings
                .newBuilder()
                .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            admin.createKeyRing("projects/" + PROJECT + "/locations/global", "orders", KeyRing.getDefaultInstance());
            admin.createCryptoKey(KEY_RING, "orders-data", com.google.cloud.kms.v1.CryptoKey.newBuilder()
                    .setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
            admin.createCryptoKey(KEY_RING, "orders-signing", com.google.cloud.kms.v1.CryptoKey.newBuilder()
                    .setPurpose(CryptoKeyPurpose.ASYMMETRIC_SIGN).setVersionTemplate(CryptoKeyVersionTemplate
                            .newBuilder().setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)).build());
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void keysFromPropertiesSealSignAndOpenOnCloudKms() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            KeyStore kms = context.getBean("kms", KeyStore.class);
            CryptoKey data = context.getBean("data", CryptoKey.class);
            CryptoKey tokens = context.getBean("tokens", CryptoKey.class);

            SealedData sealed = data.encrypt(DATA, AAD);
            Signature signature = tokens.sign(DATA);
            ReactiveCryptoKey reactive = context.getBean("dataReactive", ReactiveCryptoKey.class);

            assertThat(sealed.keys().get(0).keyId()).isEqualTo("gcp-kms:" + KEY_RING + "/cryptoKeys/orders-data");
            assertThat(kms.decrypt(sealed, AAD)).isEqualTo(DATA);
            assertThat(reactive.decrypt(sealed, AAD).block(Duration.ofSeconds(10))).isEqualTo(DATA);
            assertThat(tokens.verify(DATA, signature)).isTrue();
            assertThat(kms.statistics().metadataReads()).isPositive(); // check-on-startup read it
        });
    }

    @Test
    void checkOnStartupFailsForAMissingKey() {
        runner.withPropertyValues("halyx.keys.data.name=missing").run(context -> {
            assertThat(context).hasFailed();
            // The portable error keeps the SDK's as its cause, so it's in the chain rather than at its root.
            assertThat(causes(context.getStartupFailure())).hasAtLeastOneElementOfType(NotFoundException.class);
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("halyx.keys.data.check-on-startup: key 'missing' can't be used");
        });
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
