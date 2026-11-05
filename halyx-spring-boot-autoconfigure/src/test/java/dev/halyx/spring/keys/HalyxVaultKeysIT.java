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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Key stores and keys from properties on a Vault dev server's Transit engine, on Boot 3.5 and 4. */
@Testcontainers(disabledWithoutDocker = true)
class HalyxVaultKeysIT {

    private static final String ROOT = "root-token";
    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> VAULT = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
            .withExposedPorts(8200)
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
            .withPropertyValues(
                    "halyx.providers.vault.type=vault",
                    "halyx.providers.vault.endpoint=" + address(),
                    "halyx.providers.vault.credentials.type=static",
                    "halyx.providers.vault.credentials.id=token",
                    "halyx.providers.vault.credentials.secret=" + ROOT,
                    "halyx.key-stores.kms.prefix=orders-",
                    "halyx.keys.data.check-on-startup=true",
                    "halyx.keys.tokens.name=signing",
                    "halyx.keys.tokens.signing-algorithm=ecdsa-p256-sha256");

    @BeforeAll
    static void keys() throws Exception {
        root("sys/mounts/transit", "{\"type\":\"transit\"}");
        root("transit/keys/orders-data", "{\"type\":\"aes256-gcm96\"}");
        root("transit/keys/orders-signing", "{\"type\":\"ecdsa-p256\"}");
    }

    private static URI address() {
        return URI.create("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    }

    @Test
    void keysFromPropertiesSealSignAndOpenOnTransit() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            KeyStore kms = context.getBean("kms", KeyStore.class);
            CryptoKey data = context.getBean("data", CryptoKey.class);
            CryptoKey tokens = context.getBean("tokens", CryptoKey.class);

            SealedData sealed = data.encrypt(DATA, AAD);
            Signature signature = tokens.sign(DATA);
            ReactiveCryptoKey reactive = context.getBean("dataReactive", ReactiveCryptoKey.class);

            assertThat(sealed.keys().get(0).keyId()).isEqualTo("vault-transit:transit/orders-data");
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
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(NotFoundException.class);
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("halyx.keys.data.check-on-startup: key 'missing' can't be used");
        });
    }

    private static void root(String path, String body) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(address().resolve("/v1/" + path))
                    .header("X-Vault-Token", ROOT).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s: %s", path, response.body()).isLessThan(300);
        }
    }
}
