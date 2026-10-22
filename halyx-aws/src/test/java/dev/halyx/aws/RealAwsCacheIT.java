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
package dev.halyx.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.Halyx;
import dev.halyx.CloudContext;
import dev.halyx.cache.CacheAuth;
import dev.halyx.cache.CacheConfig;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.redis.RedisCaches;
import dev.halyx.tck.CacheConformanceTest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache kit against real ElastiCache for Valkey (tier 3): TLS with full certificate and hostname checks,
 * and IAM user authentication. Runs only with {@code -Dhalyx.real.aws.cache=true}, normally through
 * {@code TIER=3 cloud-testing/scripts/aws-real-cloud.sh}. ElastiCache has no public endpoints, so the script tunnels a
 * local port through a bastion and points the cache's real hostname at 127.0.0.1 with a JVM hosts file:
 * the client connects to the real name and verifies the real certificate. Lettuce's own DNS client ignores
 * JVM hosts files, so these caches resolve through the JVM ({@code redis.dns-resolver=jdk}).
 */
@EnabledIfSystemProperty(named = "halyx.real.aws.cache", matches = "true")
class RealAwsCacheIT {

    private static final String HOST = System.getProperty("halyx.real.aws.cache.host");
    private static final int PORT = Integer.getInteger("halyx.real.aws.cache.port", 6379);
    private static final String CACHE_NAME = System.getProperty("halyx.real.aws.cache.name");
    private static final String APP_USER = System.getProperty("halyx.real.aws.cache.app-user");
    private static final String DENIED_USER = System.getProperty("halyx.real.aws.cache.denied-user");

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("aws", ProviderConfig.of("aws")
                .region(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"))).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    static URI endpoint() {
        return URI.create("rediss://" + HOST + ":" + PORT);
    }

    /**
     * The cache endpoint, resolved through the JVM so the tunnel's hosts file applies. A 1 MiB value through the
     * SSH tunnel takes about two seconds, the default command timeout, so the tests allow longer.
     */
    static CacheConfig.Builder cache() {
        return CacheConfig.builder(endpoint())
                .property(RedisCaches.DNS_RESOLVER, "jdk")
                .property(RedisCaches.COMMAND_TIMEOUT, "PT10S");
    }

    static CacheConfig.Builder iam(String user) {
        return cache()
                .auth(CacheAuth.iam(user))
                .property(AwsProperties.ELASTICACHE_CACHE_NAME, CACHE_NAME);
    }

    @Nested
    class IamAuth extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("aws");
        }

        @Override
        protected CacheConfig.Builder config() {
            return iam(APP_USER);
        }
    }

    @Test
    void iamUserReadsAndWritesOverVerifiedTls() {
        var cache = halyx.provider("aws").cache(iam(APP_USER).namespace("tier3-smoke").build());
        cache.put("greeting", "hello");

        assertThat(cache.get("greeting")).contains("hello");
        cache.delete("greeting");
    }

    @Test
    void iamUserWithoutConnectPermissionIsRejected() {
        // The user exists and is IAM-enabled, but the caller's policy grants elasticache:Connect for APP_USER only.
        var denied = halyx.provider("aws").cache(iam(DENIED_USER).namespace("denied").build());

        assertThatThrownBy(() -> denied.get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthenticatedConnectionIsRejected() {
        var anonymous = halyx.provider("aws").cache(cache().namespace("anonymous").build());

        assertThatThrownBy(() -> anonymous.get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tokenSignedForAnotherCacheIsRejected() {
        var wrongCache = halyx.provider("aws").cache(cache()
                .auth(CacheAuth.iam(APP_USER))
                .property(AwsProperties.ELASTICACHE_CACHE_NAME, CACHE_NAME + "-other")
                .namespace("wrong-cache").build());

        assertThatThrownBy(() -> wrongCache.get("k")).isInstanceOf(AccessDeniedException.class);
    }
}
