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
package dev.halyx.azure;

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
import dev.halyx.tck.CacheConformanceTest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache kit against real Azure Managed Redis (tier 3): Microsoft Entra ID authentication only (access keys
 * are off), over TLS on the public endpoint with full certificate and host name checks. Runs only with
 * {@code -Dhalyx.real.azure.cache=true}, normally through {@code TIER=3 scripts/azure-real-cloud.sh}.
 * The username is the service principal's object id, taken from the token.
 */
@EnabledIfSystemProperty(named = "halyx.real.azure.cache", matches = "true")
class RealAzureCacheIT {

    private static final String HOST = System.getProperty("halyx.real.azure.cache.host");
    private static final int PORT = Integer.getInteger("halyx.real.azure.cache.port", 10000);

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    static CloudContext azure() {
        return halyx.provider("azure");
    }

    static CacheConfig.Builder endpoint() {
        return CacheConfig.builder(URI.create("rediss://" + HOST + ":" + PORT));
    }

    @Nested
    class EntraId extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected CacheConfig.Builder config() {
            return endpoint().auth(CacheAuth.iam());
        }
    }

    @Test
    void entraTokenReadsAndWrites() {
        var cache = azure().cache(endpoint().auth(CacheAuth.iam()).namespace("tier3-smoke").build());
        cache.put("greeting", "hello");

        assertThat(cache.get("greeting")).contains("hello");
        cache.delete("greeting");
    }

    @Test
    void unauthenticatedConnectionIsRejected() {
        var anonymous = azure().cache(endpoint().namespace("anonymous").build());

        assertThatThrownBy(() -> anonymous.get("k")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void forgedTokenIsRejected() {
        var forged = azure().cache(endpoint().auth(CacheAuth.password("00000000-0000-0000-0000-000000000000",
                "eyJhbGciOiJub25lIn0.eyJvaWQiOiIwIn0.")).namespace("forged").build());

        assertThatThrownBy(() -> forged.get("k")).isInstanceOf(AccessDeniedException.class);
    }
}
