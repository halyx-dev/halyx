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
package dev.halyx.gcp;

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
 * The cache kit against real Memorystore for Valkey (tier 3): IAM authentication with the identity's access
 * token, over TLS checked against the instance's private CA. Runs only with {@code -Dhalyx.real.gcp.cache=true},
 * normally through {@code TIER=3 cloud-testing/scripts/gcp-real-cloud.sh}. Memorystore has no public endpoints, so the
 * script forwards a local port through a bastion. The certificate names the instance's private endpoint, not
 * the tunnel's 127.0.0.1, so these caches check the chain only ({@code redis.verify-peer=ca}).
 */
@EnabledIfSystemProperty(named = "halyx.real.gcp.cache", matches = "true")
class RealGcpCacheIT {

    private static final String PROJECT = System.getProperty("halyx.real.gcp.project");
    private static final int PORT = Integer.getInteger("halyx.real.gcp.cache.port", 16380);
    private static final String CA = System.getProperty("halyx.real.gcp.cache.ca-certificate");

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp")
                .property(GcpProperties.PROJECT_ID, PROJECT)).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    static CloudContext gcp() {
        return halyx.provider("gcp");
    }

    /**
     * The tunnel, trusting the instance's CA and checking the chain but not the name. A 1 MiB value through the SSH
     * tunnel takes about two seconds, the default command timeout, so the tests allow longer.
     */
    static CacheConfig.Builder tunnel() {
        return CacheConfig.builder(URI.create("rediss://127.0.0.1:" + PORT))
                .property(RedisCaches.CA_CERTIFICATE, CA)
                .property(RedisCaches.VERIFY_PEER, "ca")
                .property(RedisCaches.COMMAND_TIMEOUT, "PT10S");
    }

    @Nested
    class IamAuth extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected CacheConfig.Builder config() {
            return tunnel().auth(CacheAuth.iam());
        }
    }

    @Test
    void iamTokenReadsAndWrites() {
        var cache = gcp().cache(tunnel().auth(CacheAuth.iam()).namespace("tier3-smoke").build());
        cache.put("greeting", "hello");

        assertThat(cache.get("greeting")).contains("hello");
        cache.delete("greeting");
    }

    @Test
    void unauthenticatedConnectionIsRejected() {
        var anonymous = gcp().cache(tunnel().namespace("anonymous").build());

        assertThatThrownBy(() -> anonymous.get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidTokenIsRejected() {
        var forged = gcp().cache(tunnel().auth(CacheAuth.password("ya29.not-a-real-token")).namespace("forged").build());

        assertThatThrownBy(() -> forged.get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void fullVerificationRejectsTheTunnelAddress() {
        // Why the tunnel needs verify-peer=ca: the certificate doesn't name 127.0.0.1.
        var full = gcp().cache(CacheConfig.builder(URI.create("rediss://127.0.0.1:" + PORT))
                .property(RedisCaches.CA_CERTIFICATE, CA).auth(CacheAuth.iam()).namespace("full").build());

        assertThatThrownBy(() -> full.get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void chainIsRejectedWithoutTheInstanceCa() {
        // Memorystore's server CA is private: the JDK's public roots don't trust it.
        var noCa = gcp().cache(CacheConfig.builder(URI.create("rediss://127.0.0.1:" + PORT))
                .property(RedisCaches.VERIFY_PEER, "ca").auth(CacheAuth.iam()).namespace("no-ca").build());

        assertThatThrownBy(() -> noCa.get("k")).isInstanceOf(AccessDeniedException.class);
    }
}
