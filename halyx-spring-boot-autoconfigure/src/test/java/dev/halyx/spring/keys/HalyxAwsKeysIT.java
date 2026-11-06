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

import dev.halyx.error.NotFoundException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.reactor.ReactiveCryptoKey;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Key stores and keys from properties on AWS KMS emulated by Floci, on Boot 3.5 and 4. */
@Testcontainers(disabledWithoutDocker = true)
class HalyxAwsKeysIT {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static KmsClient admin;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
            .withPropertyValues(
                    "halyx.providers.aws.type=aws",
                    "halyx.providers.aws.region=us-east-1",
                    "halyx.providers.aws.endpoint=" + endpoint(),
                    "halyx.providers.aws.credentials.type=static",
                    "halyx.providers.aws.credentials.id=test",
                    "halyx.providers.aws.credentials.secret=test",
                    "halyx.key-stores.kms.prefix=orders-",
                    "halyx.keys.data.check-on-startup=true",
                    "halyx.keys.tokens.name=signing",
                    "halyx.keys.tokens.signing-algorithm=ecdsa-p256-sha256");

    @BeforeAll
    static void keys() {
        admin = KmsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        create("orders-data", KeySpec.SYMMETRIC_DEFAULT, KeyUsageType.ENCRYPT_DECRYPT);
        create("orders-signing", KeySpec.ECC_NIST_P256, KeyUsageType.SIGN_VERIFY);
    }

    @AfterAll
    static void close() {
        if (admin != null) {
            admin.close();
        }
    }

    private static URI endpoint() {
        return URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
    }

    private static void create(String alias, KeySpec spec, KeyUsageType usage) {
        String keyId = admin.createKey(b -> b.keySpec(spec).keyUsage(usage)).keyMetadata().keyId();
        admin.createAlias(b -> b.aliasName("alias/" + alias).targetKeyId(keyId));
    }

    @Test
    void keysFromPropertiesSealSignAndOpenOnKms() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            KeyStore kms = context.getBean("kms", KeyStore.class);
            CryptoKey data = context.getBean("data", CryptoKey.class);
            CryptoKey tokens = context.getBean("tokens", CryptoKey.class);
            String arn = admin.describeKey(b -> b.keyId("alias/orders-data")).keyMetadata().arn();

            SealedData sealed = data.encrypt(DATA, AAD);
            Signature signature = tokens.sign(DATA);
            ReactiveCryptoKey reactive = context.getBean("dataReactive", ReactiveCryptoKey.class);

            assertThat(sealed.keys().get(0).keyId()).isEqualTo("aws-kms:" + arn);
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
