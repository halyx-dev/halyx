package org.kamranzafar.stratum.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.error.AccessDeniedException;
import org.kamranzafar.stratum.tck.CacheConformanceTest;
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

    static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        stratum.close();
    }

    static URI endpoint(GenericContainer<?> container) {
        return URI.create("redis://" + container.getHost() + ":" + container.getMappedPort(6379));
    }

    @Nested
    class WithoutAuth extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("redis");
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
            return stratum.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint(SECURED)).auth(CacheAuth.password("s3cret"));
        }
    }

    @Test
    void wrongPasswordIsAccessDenied() {
        var config = CacheConfig.builder(endpoint(SECURED)).auth(CacheAuth.password("wrong")).build();

        assertThatThrownBy(() -> stratum.provider("redis").cache(config).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingPasswordIsAccessDenied() {
        var config = CacheConfig.builder(endpoint(SECURED)).namespace("no-auth").build();

        assertThatThrownBy(() -> stratum.provider("redis").cache(config).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
