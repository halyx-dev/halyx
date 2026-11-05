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
package dev.halyx.vault;

import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Transit driver's checks and error mapping, without a Vault server: nothing here reaches the network. */
class VaultTransitDriverTest {

    private final VaultClient client = new VaultClient(URI.create("http://127.0.0.1:1"), Optional.empty(),
            VaultLogin.token("t"), Optional.empty());
    private final VaultTransitDriver driver = new VaultTransitDriver(client, "transit");

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void entriesInTheMountAreInLocation() {
        assertThat(driver.inLocation("vault-transit:transit/orders")).isTrue();
        assertThat(driver.inLocation("vault-transit:transit-eu/orders")).isFalse();
        assertThat(driver.inLocation("vault-transit:transit/")).isFalse();
        assertThat(driver.inLocation("vault-transit:transit/a/b")).isFalse();
        assertThat(driver.inLocation("memory:transit/orders")).isFalse();
        assertThat(driver.unwraps(WrapAlgorithm.VAULT_TRANSIT)).isTrue();
        assertThat(driver.unwraps(WrapAlgorithm.AWS_KMS)).isFalse();
    }

    @Test
    void anEntryWhoseVersionDisagreesWithItsCiphertextIsRefusedBeforeAnyCall() {
        WrappedKey changed = new WrappedKey("vault-transit:transit/orders", Optional.of("2"),
                WrapAlgorithm.VAULT_TRANSIT, "vault:v1:abc=".getBytes(StandardCharsets.UTF_8));
        WrappedKey notTransit = new WrappedKey("vault-transit:transit/orders", Optional.of("1"),
                WrapAlgorithm.VAULT_TRANSIT, "garbage".getBytes(StandardCharsets.UTF_8));
        WrappedKey elsewhere = new WrappedKey("vault-transit:other/orders", Optional.of("1"),
                WrapAlgorithm.VAULT_TRANSIT, "vault:v1:abc=".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> driver.unwrapKey(changed)).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> driver.unwrapKey(notTransit)).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> driver.unwrapKey(elsewhere)).isInstanceOf(InvalidArgumentException.class);
        assertThat(driver.unwrapKeyAsync(changed, Runnable::run)).isCompletedExceptionally();
    }

    @Test
    void vaultErrorsMapToPortableOnes() {
        assertThat(driver.translate(error(400, "cipher: message authentication failed")))
                .isInstanceOf(DataIntegrityException.class);
        assertThat(driver.translate(error(400, "invalid ciphertext: no prefix")))
                .isInstanceOf(DataIntegrityException.class);
        assertThat(driver.translate(error(400, "ciphertext or signature version is disallowed by policy (too old)")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(driver.translate(error(400, "encryption key not found"))).isInstanceOf(NotFoundException.class);
        assertThat(driver.translate(error(400, "missing input"))).isInstanceOf(InvalidArgumentException.class);
        assertThat(driver.translate(error(403, "permission denied"))).isInstanceOf(AccessDeniedException.class)
                .satisfies(e -> assertThat(e.isRetryable()).isFalse());
        assertThat(driver.translate(error(404, ""))).isInstanceOf(NotFoundException.class);
        assertThat(driver.translate(error(429, "rate limited"))).isInstanceOf(ThrottledException.class);
        assertThat(driver.translate(error(503, "Vault is sealed"))).isInstanceOf(UnavailableException.class);
    }

    private static VaultHttpException error(int status, String message) {
        return new VaultHttpException(status, "{\"errors\":[\"" + message + "\"]}");
    }
}
