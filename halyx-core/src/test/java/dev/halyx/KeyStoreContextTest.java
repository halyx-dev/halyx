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
package dev.halyx;

import dev.halyx.config.ProviderConfig;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How key stores sit in a context: support per provider, the connect re-entry guard, and the order of shutdown. */
class KeyStoreContextTest {

    /** A provider whose connection offers no services, and records when it closes. */
    private static final class BareProvider implements CloudProvider {
        private final String id;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable onConnect;

        BareProvider(String id, Runnable onConnect) {
            this.id = id;
            this.onConnect = onConnect;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ProviderConnection connect(ProviderConfig config) {
            onConnect.run();
            return () -> closed.set(true);
        }
    }

    @Test
    void aProviderWithoutKeyManagementSaysSo() {
        try (Halyx halyx = Halyx.builder().plugin(new BareProvider("bare", () -> { }))
                .register("bare", ProviderConfig.of("bare")).build()) {
            assertThatThrownBy(() -> halyx.provider("bare").keyStore())
                    .isInstanceOf(UnsupportedFeatureException.class).hasMessageContaining("Key management");
        }
    }

    @Test
    void aLoginThatNeedsItsOwnConnectionFailsClearlyInsteadOfRecursing() {
        AtomicReference<Halyx> self = new AtomicReference<>();
        BareProvider looping = new BareProvider("looping",
                () -> self.get().provider("looping").parameterStore(ParameterStoreConfig.secrets().build()));
        try (Halyx halyx = Halyx.builder().plugin(looping).register("looping", ProviderConfig.of("looping")).build()) {
            self.set(halyx);

            assertThatThrownBy(() -> halyx.provider("looping").keyStore())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Provider 'looping' needs itself to log in");
        }
    }

    @Test
    void closingStopsEveryContextsListenersBeforeAnyClientCloses() throws InterruptedException {
        BareProvider keys = new BareProvider("vaultlike", () -> { });
        AtomicBoolean sawClosedClient = new AtomicBoolean();
        CountDownLatch handling = new CountDownLatch(1);
        // The context holding the "key" is registered first, so a one-pass close would close it first.
        Halyx halyx = Halyx.builder().plugin(keys)
                .register("keys", ProviderConfig.of("vaultlike"))
                .register("orders", ProviderConfig.of("memory"))
                .build();
        assertThatThrownBy(() -> halyx.provider("keys").keyStore()).isInstanceOf(UnsupportedFeatureException.class);
        CloudContext orders = halyx.provider("orders");
        orders.subscription("orders").listen(message -> {
            handling.countDown();
            Thread.sleep(300);
            sawClosedClient.set(keys.closed.get()); // a handler decrypting with the other context's key
        });
        orders.publisher("orders").publish("sealed order");
        assertThat(handling.await(10, TimeUnit.SECONDS)).isTrue();

        halyx.close();

        assertThat(sawClosedClient).as("the other context's client was still open while the handler ran").isFalse();
        assertThat(keys.closed).isTrue();
    }

    @Test
    void keysOnTheMemoryProviderAreSharedByLocationAndClearedOnClose() {
        try (Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build()) {
            CloudContext context = halyx.provider("local");
            KeyStore store = context.keyStore();
            store.unwrap(InMemoryKeys.class).create("data", KeyPurpose.ENCRYPTION);
            byte[] aad = "a".getBytes(StandardCharsets.UTF_8);

            var sealed = store.key("data").encrypt(new byte[] {1, 2, 3}, aad);

            KeyStore prefixed = context.keyStore(KeyStoreConfig.builder().prefix("d").build());
            assertThat(prefixed.key("ata").decrypt(sealed, aad)).containsExactly(1, 2, 3);
            context.close();
            assertThatThrownBy(context::keyStore).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }
}
