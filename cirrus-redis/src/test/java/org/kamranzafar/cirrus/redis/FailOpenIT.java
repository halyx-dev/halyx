package org.kamranzafar.cirrus.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.UnavailableException;
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

    private static Cirrus cirrus;
    private static URI deadEndpoint;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            deadEndpoint = URI.create("redis://localhost:" + socket.getLocalPort()); // nothing listens once closed
        }
        cirrus = Cirrus.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
    }

    private static CacheConfig.Builder config(URI endpoint) {
        return CacheConfig.builder(endpoint).property(RedisCaches.COMMAND_TIMEOUT, "PT0.5S");
    }

    @Test
    void failOpenCacheDegradesToMissesAndLoads() {
        Cache<String> cache = cirrus.provider("redis").cache(config(deadEndpoint).namespace("a").failOpen(true).build());
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
        Cache<String> cache = cirrus.provider("redis").cache(config(deadEndpoint).namespace("b").failOpen(true).build());

        assertThatThrownBy(() -> cache.putIfAbsent("k", "v")).isInstanceOf(UnavailableException.class);
        assertThatThrownBy(() -> cache.increment("k", 1)).isInstanceOf(UnavailableException.class);
        assertThatThrownBy(() -> cache.tryLock("l", Duration.ofSeconds(1))).isInstanceOf(UnavailableException.class);
    }

    @Test
    void openBreakerSkipsTheServer() {
        Cache<String> cache = cirrus.provider("redis").cache(config(deadEndpoint).namespace("c").failOpen(true).build());
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
        Cache<String> cache = cirrus.provider("redis").cache(config(deadEndpoint).namespace("d").build());

        assertThatThrownBy(() -> cache.get("k")).isInstanceOf(UnavailableException.class);
    }

    @Test
    void cacheCreatedWhileServerIsDownWorksOnceItIsUp() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Cache<String> cache = cirrus.provider("redis").cache(config(URI.create("redis://localhost:" + port)).namespace("e").build());
        assertThatThrownBy(() -> cache.get("k")).isInstanceOf(UnavailableException.class);

        try (GenericContainer<?> valkey = new GenericContainer<>(ValkeyCacheConformanceIT.VALKEY).withExposedPorts(6379)) {
            valkey.setPortBindings(List.of(port + ":6379"));
            valkey.start();
            cache.put("k", "v");
            assertThat(cache.get("k")).hasValue("v");
        }
    }
}
