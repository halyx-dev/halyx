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
package org.kamranzafar.cirrus.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.tck.CacheConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cache TCK on a real Valkey server, through the shared Redis driver. */
@Testcontainers(disabledWithoutDocker = true)
class ValkeyCacheConformanceIT {

    static final DockerImageName VALKEY = DockerImageName.parse(System.getProperty("valkey.image", "valkey/valkey:9.1.2-alpine"));

    @Container
    static final GenericContainer<?> OPEN = new GenericContainer<>(VALKEY).withExposedPorts(6379);

    @Container
    static final GenericContainer<?> SECURED = new GenericContainer<>(VALKEY).withExposedPorts(6379)
            .withCommand("valkey-server", "--requirepass", "s3cret");

    static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
    }

    static URI endpoint(GenericContainer<?> container) {
        return URI.create("redis://" + container.getHost() + ":" + container.getMappedPort(6379));
    }

    @Nested
    class WithoutAuth extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint(OPEN));
        }
    }

    @Nested
    class WithPassword extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint(SECURED)).auth(CacheAuth.password("s3cret"));
        }
    }

    @Test
    void wrongPasswordIsAccessDenied() {
        var config = CacheConfig.builder(endpoint(SECURED)).auth(CacheAuth.password("wrong")).build();

        assertThatThrownBy(() -> cirrus.provider("redis").cache(config).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingPasswordIsAccessDenied() {
        var config = CacheConfig.builder(endpoint(SECURED)).namespace("no-auth").build();

        assertThatThrownBy(() -> cirrus.provider("redis").cache(config).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
