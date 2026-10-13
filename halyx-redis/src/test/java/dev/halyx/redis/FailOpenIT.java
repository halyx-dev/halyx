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
import org.junit.jupiter.api.Test;
import dev.halyx.Halyx;
import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheConfig;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.UnavailableException;
import org.testcontainers.containers.GenericContainer;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A cache server that is down: fail-open degrades to misses; strict caches fail; recovery reconnects. */
class FailOpenIT {

    private static Halyx halyx;
    private static URI deadEndpoint;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            deadEndpoint = URI.create("redis://localhost:" + socket.getLocalPort()); // nothing listens once closed
        }
        halyx = Halyx.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    private static CacheConfig.Builder config(URI endpoint) {
        return CacheConfig.builder(endpoint).property(RedisCaches.COMMAND_TIMEOUT, "PT0.5S");
    }

    @Test
    void failOpenCacheDegradesToMissesAndLoads() {
        Cache<String> cache = halyx.provider("redis").cache(config(deadEndpoint).namespace("a").failOpen(true).build());
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("k")).isEmpty();
        assertThat(cache.getOrLoad("k", () -> "loaded-" + loads.incrementAndGet())).isEqualTo("loaded-1");
        cache.put("k", "v");
        cache.delete("k");
        assertThat(cache.exists("k")).isFalse();
        assertThat(cache.statistics().failOpenSkips()).isPositive();
    }

    @Test
    void correctnessCriticalOperationsStillFail() {
        Cache<String> cache = halyx.provider("redis").cache(config(deadEndpoint).namespace("b").failOpen(true).build());

        assertThatThrownBy(() -> cache.putIfAbsent("k", "v")).isInstanceOf(UnavailableException.class);
        assertThatThrownBy(() -> cache.increment("k", 1)).isInstanceOf(UnavailableException.class);
        assertThatThrownBy(() -> cache.tryLock("l", Duration.ofSeconds(1))).isInstanceOf(UnavailableException.class);
    }

    @Test
    void openBreakerSkipsTheServer() {
        Cache<String> cache = halyx.provider("redis").cache(config(deadEndpoint).namespace("c").failOpen(true).build());
        for (int i = 0; i < 6; i++) {
            cache.get("k");
        }

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(cache.get("k")).isEmpty();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void strictCacheReportsUnavailability() {
        Cache<String> cache = halyx.provider("redis").cache(config(deadEndpoint).namespace("d").build());

        assertThatThrownBy(() -> cache.get("k")).isInstanceOf(UnavailableException.class);
    }

    @Test
    void cacheCreatedWhileServerIsDownWorksOnceItIsUp() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Cache<String> cache = halyx.provider("redis").cache(config(URI.create("redis://localhost:" + port)).namespace("e").build());
        assertThatThrownBy(() -> cache.get("k")).isInstanceOf(UnavailableException.class);

        try (GenericContainer<?> valkey = new GenericContainer<>(ValkeyCacheConformanceIT.VALKEY).withExposedPorts(6379)) {
            valkey.setPortBindings(List.of(port + ":6379"));
            valkey.start();
            cache.put("k", "v");
            assertThat(cache.get("k")).hasValue("v");
        }
    }
}
