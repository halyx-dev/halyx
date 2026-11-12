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

import com.azure.core.credential.AccessToken;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.models.CreateEcKeyOptions;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import dev.halyx.azure.parameters.ForceHttpPolicy;
import dev.halyx.error.NotFoundException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.reactor.ReactiveCryptoKey;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.security.HalyxJwtAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key stores and keys from properties on Key Vault keys emulated by Floci-az, on Boot 3.5 and 4. The store has both
 * data key caches on, as the docs recommend for Azure, where every wrap and unwrap is an RSA operation on the vault.
 */
@Testcontainers(disabledWithoutDocker = true)
class HalyxAzureKeysIT {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withExposedPorts(4577)
            .waitingFor(Wait.forListeningPort());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class,
                    HalyxJwtAutoConfiguration.class))
            .withPropertyValues(
                    "halyx.providers.azure.type=azure",
                    "halyx.providers.azure.credentials.type=anonymous",
                    "halyx.key-stores.kms.location=" + vaultUrl(),
                    "halyx.key-stores.kms.prefix=orders-",
                    "halyx.key-stores.kms.data-key-reuse.max-age=5m",
                    "halyx.key-stores.kms.data-key-reuse.max-messages=10000",
                    "halyx.key-stores.kms.data-key-cache.ttl=5m",
                    "halyx.key-stores.kms.data-key-cache.max-entries=1000",
                    "halyx.key-stores.kms.properties[azure.emulator.insecure-http]=true",
                    "halyx.keys.data.check-on-startup=true",
                    "halyx.keys.tokens.name=signing",
                    "halyx.keys.tokens.signing-algorithm=ecdsa-p256-sha256");

    @BeforeAll
    static void keys() {
        KeyClient admin = new KeyClientBuilder().vaultUrl(vaultUrl())
                .credential(request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1))))
                .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient();
        admin.createRsaKey(new CreateRsaKeyOptions("orders-data").setKeySize(2048)
                .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
        admin.createEcKey(new CreateEcKeyOptions("orders-signing").setCurveName(KeyCurveName.P_256)
                .setKeyOperations(KeyOperation.SIGN, KeyOperation.VERIFY));
    }

    private static String vaultUrl() {
        // https is what the SDKs require; the emulator policy downgrades it to http on the wire.
        return "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4577) + "/devstoreaccount1-keyvault";
    }

    @Test
    void keysFromPropertiesSealSignAndOpenOnKeyVault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            KeyStore kms = context.getBean("kms", KeyStore.class);
            CryptoKey data = context.getBean("data", CryptoKey.class);
            CryptoKey tokens = context.getBean("tokens", CryptoKey.class);

            SealedData sealed = data.encrypt(DATA, AAD);
            SealedData again = data.encrypt(DATA, AAD);
            Signature signature = tokens.sign(DATA);
            ReactiveCryptoKey reactive = context.getBean("dataReactive", ReactiveCryptoKey.class);

            assertThat(sealed.keys().get(0).keyId()).isEqualTo("azure-kv:" + vaultUrl() + "/keys/orders-data");
            assertThat(kms.decrypt(sealed, AAD)).isEqualTo(DATA);
            assertThat(kms.decrypt(again, AAD)).isEqualTo(DATA);
            assertThat(reactive.decrypt(sealed, AAD).block(Duration.ofSeconds(10))).isEqualTo(DATA);
            assertThat(tokens.verify(DATA, signature)).isTrue();
            assertThat(kms.statistics().metadataReads()).isPositive(); // check-on-startup read it
            // One wrap and one unwrap on the vault: the second message reused the data key, the later opens hit the cache.
            assertThat(kms.statistics().wraps()).isEqualTo(1);
            assertThat(kms.statistics().unwraps()).isEqualTo(1);
            assertThat(kms.statistics().unwrapCacheHits()).isEqualTo(2);
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

    @Test
    void jwtsAreSignedWithTheKeyVersionAsKid() throws Exception {
        runner.withPropertyValues(JwtChecks.properties("tokens")).run(context -> {
            assertThat(context).hasNotFailed();
            String kid = JwtChecks.signAndVerify(context, "ES256");
            assertThat(kid).isEqualTo(context.getBean("tokens", CryptoKey.class).metadata().currentVersion()
                    .orElseThrow());
        });
    }
}
