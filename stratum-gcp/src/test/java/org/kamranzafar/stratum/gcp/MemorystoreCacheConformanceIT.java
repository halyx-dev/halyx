package org.kamranzafar.stratum.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.tck.CacheConformanceTest;
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

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder().register("gcp", ProviderConfig.of("gcp")).build();
    }

    @AfterAll
    static void stop() {
        stratum.close();
    }

    private static URI endpoint() {
        return URI.create("redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    }

    @Nested
    class WithAuthString extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("gcp");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint()).auth(CacheAuth.password("memorystore-auth"));
        }
    }

    @Test
    void iamNeedsApplicationDefaultCredentials() {
        try (Stratum anonymous = Stratum.builder()
                .register("gcp", ProviderConfig.of("gcp").credentials(Credentials.anonymous())).build()) {
            var iam = CacheConfig.builder(endpoint()).auth(CacheAuth.iam()).build();

            assertThatThrownBy(() -> anonymous.provider("gcp").cache(iam)).hasMessageContaining("Application Default Credentials");
        }
    }
}
