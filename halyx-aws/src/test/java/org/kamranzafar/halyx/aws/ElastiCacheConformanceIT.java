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
package org.kamranzafar.halyx.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.CloudContext;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.cache.CacheAuth;
import org.kamranzafar.halyx.cache.CacheConfig;
import org.kamranzafar.halyx.config.Credentials;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.error.AccessDeniedException;
import org.kamranzafar.halyx.tck.CacheConformanceTest;
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

    private static final String GROUP = "halyx-tck";
    private static final String USER = "halyx-app";
    private static final String AUTH_TOKEN = "halyx-auth-token-0123456789";

    private static final Network NETWORK = Network.newNetwork();
    private static final GenericContainer<?> FLOCI = ElastiCacheFloci.container(NETWORK);

    private static Halyx halyx;
    private static ElastiCacheClient elastiCache;
    private static URI cacheEndpoint;

    @BeforeAll
    static void start() throws Exception {
        ElastiCacheFloci.configureDockerNetwork(FLOCI, NETWORK);
        FLOCI.start();
        elastiCache = ElastiCacheFloci.client(FLOCI);
        elastiCache.createReplicationGroup(b -> b.replicationGroupId(GROUP).replicationGroupDescription("Halyx TCK")
                .engine("valkey").authToken(AUTH_TOKEN));
        int port = awaitAvailable().nodeGroups().getFirst().primaryEndpoint().port();
        cacheEndpoint = URI.create("redis://" + FLOCI.getHost() + ":" + port);

        halyx = Halyx.builder()
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
        if (halyx != null) {
            halyx.close();
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
            return halyx.provider("aws");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(cacheEndpoint).auth(CacheAuth.password(AUTH_TOKEN));
        }
    }

    @Test
    void wrongAuthTokenIsRejected() {
        var wrong = CacheConfig.builder(cacheEndpoint).auth(CacheAuth.password("not-the-token")).build();

        assertThatThrownBy(() -> halyx.provider("aws").cache(wrong).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthenticatedConnectionIsRejected() {
        var anonymous = CacheConfig.builder(cacheEndpoint).namespace("anonymous").build();

        assertThatThrownBy(() -> halyx.provider("aws").cache(anonymous).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingCacheNameFailsClearly() {
        var incomplete = CacheConfig.builder(cacheEndpoint).auth(CacheAuth.iam(USER)).namespace("x").build();

        assertThatThrownBy(() -> halyx.provider("aws").cache(incomplete))
                .hasMessageContaining(AwsProperties.ELASTICACHE_CACHE_NAME);
    }
}
