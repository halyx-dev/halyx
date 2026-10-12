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
package org.kamranzafar.halyx.redis;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.cache.Cache;
import org.kamranzafar.halyx.cache.CacheAuth;
import org.kamranzafar.halyx.cache.CacheConfig;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rotating IAM-style tokens. A token close to expiry is replaced in the background and the open
 * connection re-authenticates with it; later reconnects log in with the fresh token too.
 */
@Testcontainers(disabledWithoutDocker = true)
class CredentialRotationIT {

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(ValkeyCacheConformanceIT.VALKEY)
            .withExposedPorts(6379)
            .withCommand("valkey-server",
                    "--user", "admin", "on", ">admin", "~*", "+@all",
                    "--user", "app", "on", ">token-1", "~*", "+@all",
                    "--user", "default", "off");

    @Test
    void openConnectionReauthenticatesAndReconnectsUseFreshToken() throws Exception {
        // token-1 is already inside the refresh margin's reach: it is replaced about 1.5 s from now.
        Instant firstExpiry = Instant.now().plus(RefreshingCredentialsProvider.REFRESH_MARGIN).plusMillis(1500);
        AtomicReference<RedisLogin> issued = new AtomicReference<>(RedisLogin.token("app", "token-1", firstExpiry));
        try (Halyx halyx = Halyx.builder()
                .plugin(new RedisTestProvider(iam -> issued::get))
                .register("redis", ProviderConfig.of("redis-test"))
                .build()) {
            URI endpoint = URI.create("redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
            Cache<String> cache = halyx.provider("redis").cache(CacheConfig.builder(endpoint)
                    .auth(CacheAuth.iam()).namespace("rotation").build());
            cache.put("k", "before");
            assertThat(connectedUsers()).contains("user=app ");

            // The next token belongs to a new identity, so a re-authentication is visible server-side.
            admin("ACL", "SETUSER", "app2", "on", ">token-2", "~*", "+@all");
            issued.set(RedisLogin.token("app2", "token-2", Instant.now().plus(Duration.ofHours(1))));

            Instant deadline = Instant.now().plusSeconds(15);
            while (!connectedUsers().contains("user=app2 ")) {
                assertThat(Instant.now()).as("open connection should re-authenticate as app2").isBefore(deadline);
                Thread.sleep(200);
            }
            assertThat(cache.get("k")).hasValue("before");

            // Retire the old identity and drop every connection: the reconnect must use token-2.
            admin("ACL", "DELUSER", "app");
            admin("CLIENT", "KILL", "USER", "app2");
            assertThat(cache.get("k")).hasValue("before");
        }
    }

    private static String admin(String... command) throws Exception {
        String[] full = new String[command.length + 5];
        full[0] = "valkey-cli";
        full[1] = "--user";
        full[2] = "admin";
        full[3] = "--pass";
        full[4] = "admin";
        System.arraycopy(command, 0, full, 5, command.length);
        var result = VALKEY.execInContainer(full);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout();
    }

    private static String connectedUsers() throws Exception {
        return admin("CLIENT", "LIST") + " ";
    }
}
