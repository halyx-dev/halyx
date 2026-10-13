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
package dev.halyx.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.cache.CacheAuth;
import dev.halyx.cache.CacheConfig;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.tck.CacheConformanceTest;
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

    static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    static URI endpoint(GenericContainer<?> container) {
        return URI.create("redis://" + container.getHost() + ":" + container.getMappedPort(6379));
    }

    @Nested
    class WithoutAuth extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("redis");
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
            return halyx.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint(SECURED)).auth(CacheAuth.password("s3cret"));
        }
    }

    @Test
    void wrongPasswordIsAccessDenied() {
        var config = CacheConfig.builder(endpoint(SECURED)).auth(CacheAuth.password("wrong")).build();

        assertThatThrownBy(() -> halyx.provider("redis").cache(config).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingPasswordIsAccessDenied() {
        var config = CacheConfig.builder(endpoint(SECURED)).namespace("no-auth").build();

        assertThatThrownBy(() -> halyx.provider("redis").cache(config).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
