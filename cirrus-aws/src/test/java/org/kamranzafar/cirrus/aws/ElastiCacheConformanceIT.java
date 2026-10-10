package org.kamranzafar.cirrus.aws;

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
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.tck.CacheConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache TCK through the AWS provider on ElastiCache emulated by Floci: a real Valkey replication
 * group protected by an AUTH token. (Released Floci images cannot attach IAM users to a replication
 * group yet; IAM tokens are verified against Floci's SigV4 validator in {@link ElastiCacheIamTokenIT}.)
 */
@Testcontainers(disabledWithoutDocker = true)
class ElastiCacheConformanceIT {

    private static final String GROUP = "cirrus-tck";
    private static final String USER = "cirrus-app";
    private static final String AUTH_TOKEN = "cirrus-auth-token-0123456789";

    private static final Network NETWORK = Network.newNetwork();
    private static final GenericContainer<?> FLOCI = ElastiCacheFloci.container(NETWORK);

    private static Cirrus cirrus;
    private static ElastiCacheClient elastiCache;
    private static URI cacheEndpoint;

    @BeforeAll
    static void start() throws Exception {
        ElastiCacheFloci.configureDockerNetwork(FLOCI, NETWORK);
        FLOCI.start();
        elastiCache = ElastiCacheFloci.client(FLOCI);
        elastiCache.createReplicationGroup(b -> b.replicationGroupId(GROUP).replicationGroupDescription("Cirrus TCK")
                .engine("valkey").authToken(AUTH_TOKEN));
        int port = awaitAvailable().nodeGroups().getFirst().primaryEndpoint().port();
        cacheEndpoint = URI.create("redis://" + FLOCI.getHost() + ":" + port);

        cirrus = Cirrus.builder()
                .register("aws", ProviderConfig.of("aws")
                        .region("us-east-1")
                        .endpoint(ElastiCacheFloci.endpoint(FLOCI))
                        .credentials(Credentials.of("test", "test")))
                .build();
    }

    private static ReplicationGroup awaitAvailable() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (true) {
            ReplicationGroup group = elastiCache.describeReplicationGroups(b -> b.replicationGroupId(GROUP))
                    .replicationGroups().getFirst();
            if ("available".equals(group.status()) && !group.nodeGroups().isEmpty()) {
                return group;
            }
            assertThat(Instant.now()).as("replication group status %s", group.status()).isBefore(deadline);
            Thread.sleep(500);
        }
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
        if (elastiCache != null) {
            elastiCache.deleteReplicationGroup(b -> b.replicationGroupId(GROUP));
            elastiCache.close();
        }
        FLOCI.stop();
        NETWORK.close();
    }

    @Nested
    class WithAuthToken extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("aws");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(cacheEndpoint).auth(CacheAuth.password(AUTH_TOKEN));
        }
    }

    @Test
    void wrongAuthTokenIsRejected() {
        var wrong = CacheConfig.builder(cacheEndpoint).auth(CacheAuth.password("not-the-token")).build();

        assertThatThrownBy(() -> cirrus.provider("aws").cache(wrong).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthenticatedConnectionIsRejected() {
        var anonymous = CacheConfig.builder(cacheEndpoint).namespace("anonymous").build();

        assertThatThrownBy(() -> cirrus.provider("aws").cache(anonymous).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingCacheNameFailsClearly() {
        var incomplete = CacheConfig.builder(cacheEndpoint).auth(CacheAuth.iam(USER)).namespace("x").build();

        assertThatThrownBy(() -> cirrus.provider("aws").cache(incomplete))
                .hasMessageContaining(AwsProperties.ELASTICACHE_CACHE_NAME);
    }
}
