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
package dev.halyx.kafka;

import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StreamAuth;
import org.apache.kafka.common.security.scram.ScramExtensionsCallback;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.TextOutputCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordCallbackHandlerTest {

    private static PasswordCallbackHandler handler(StreamAuth auth) {
        PasswordCallbackHandler handler = new PasswordCallbackHandler();
        handler.configure(Map.of(KafkaStreamClients.PASSWORD, auth), "SCRAM-SHA-512", List.of());
        return handler;
    }

    @Test
    void eachLoginAsksForThePassword() throws Exception {
        AtomicReference<String> password = new AtomicReference<>("first");
        PasswordCallbackHandler handler = handler(StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", password::get));

        assertThat(login(handler)).containsExactly("app", "first");
        password.set("rotated");
        assertThat(login(handler)).containsExactly("app", "rotated");
    }

    @Test
    void aFixedPasswordIsHandedOverToo() throws Exception {
        PasswordCallbackHandler handler = handler(StreamAuth.password(SaslMechanism.PLAIN, "app", "fixed"));

        assertThat(login(handler)).containsExactly("app", "fixed");
    }

    @Test
    void aPasswordThatCantBeReadFailsTheLogin() {
        PasswordCallbackHandler handler = handler(StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", () -> {
            throw new IllegalStateException("no secret named msk/app");
        }));

        assertThatThrownBy(() -> login(handler)).isInstanceOf(IOException.class).hasMessageContaining("msk/app");
    }

    @Test
    void scramExtensionsStayEmptyAndOtherCallbacksAreRefused() throws Exception {
        PasswordCallbackHandler handler = handler(StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", "fixed"));
        ScramExtensionsCallback extensions = new ScramExtensionsCallback();

        handler.handle(new Callback[] {extensions});

        assertThat(extensions.extensions()).isEmpty();
        assertThatThrownBy(() -> handler.handle(new Callback[] {new TextOutputCallback(TextOutputCallback.INFORMATION, "x")}))
                .isInstanceOf(UnsupportedCallbackException.class);
    }

    @Test
    void onlyClientsHalyxCreatesCarryALogin() {
        assertThatThrownBy(() -> new PasswordCallbackHandler().configure(Map.of(), "PLAIN", List.of()))
                .hasMessageContaining(KafkaStreamClients.PASSWORD);
    }

    private static List<String> login(PasswordCallbackHandler handler) throws Exception {
        NameCallback name = new NameCallback("user");
        PasswordCallback password = new PasswordCallback("password", false);
        handler.handle(new Callback[] {name, password});
        return List.of(name.getName(), new String(password.getPassword()));
    }
}
