package org.kamranzafar.cirrus.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.redis.RedisCaches;
import org.kamranzafar.cirrus.tck.CacheConformanceTest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache kit against real Memorystore for Valkey (tier 3): IAM authentication with the identity's access
 * token, over TLS checked against the instance's private CA. Runs only with {@code -Dcirrus.real.gcp.cache=true},
 * normally through {@code TIER=3 scripts/gcp-real-cloud.sh}. Memorystore has no public endpoints, so the
 * script forwards a local port through a bastion. The certificate names the instance's private endpoint, not
 * the tunnel's 127.0.0.1, so these caches check the chain only ({@code redis.verify-peer=ca}).
 */
@EnabledIfSystemProperty(named = "cirrus.real.gcp.cache", matches = "true")
class RealGcpCacheIT {

    private static final String PROJECT = System.getProperty("cirrus.real.gcp.project");
    private static final int PORT = Integer.getInteger("cirrus.real.gcp.cache.port", 16380);
    private static final String CA = System.getProperty("cirrus.real.gcp.cache.ca-certificate");

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("gcp", ProviderConfig.of("gcp")
                .property(GcpProperties.PROJECT_ID, PROJECT)).build();
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    static CloudContext gcp() {
        return cirrus.provider("gcp");
    }

    /** The tunnel, trusting the instance's CA and checking the chain but not the name. */
    static CacheConfig.Builder tunnel() {
        return CacheConfig.builder(URI.create("rediss://127.0.0.1:" + PORT))
                .property(RedisCaches.CA_CERTIFICATE, CA)
                .property(RedisCaches.VERIFY_PEER, "ca");
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
