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
package org.kamranzafar.cirrus.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.tck.CacheConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache TCK through the GCP provider. Floci does not emulate Memorystore, so the server is a
 * plain Valkey container (Memorystore for Valkey runs Valkey) with an AUTH password; IAM tokens are
 * unit-tested in MemorystoreIamTokensTest.
 */
@Testcontainers(disabledWithoutDocker = true)
class MemorystoreCacheConformanceIT {

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("valkey.image", "valkey/valkey:9.1.2-alpine")))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--requirepass", "memorystore-auth");

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("gcp", ProviderConfig.of("gcp")).build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
    }

    private static URI endpoint() {
        return URI.create("redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    }

    @Nested
    class WithAuthString extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("gcp");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint()).auth(CacheAuth.password("memorystore-auth"));
        }
    }

    @Test
    void iamNeedsApplicationDefaultCredentials() {
        try (Cirrus anonymous = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp").credentials(Credentials.anonymous())).build()) {
            var iam = CacheConfig.builder(endpoint()).auth(CacheAuth.iam()).build();

            assertThatThrownBy(() -> anonymous.provider("gcp").cache(iam)).hasMessageContaining("Application Default Credentials");
        }
    }
}
