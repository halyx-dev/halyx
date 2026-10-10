package org.kamranzafar.stratum.spring.autoconfigure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.Codec;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.spring.autoconfigure.metrics.StratumCacheMetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code @Cacheable} on Stratum caches; runs on Boot 3 (Jackson 2) and Boot 4 (Jackson 3). */
class StratumCacheManagerTest {

    public record User(long id, String name) implements Serializable {
    }

    public static class UserService {
        final AtomicInteger calls = new AtomicInteger();

        @Cacheable("users")
        public User find(long id) {
            calls.incrementAndGet();
            return new User(id, "user-" + id);
        }

        @Cacheable(cacheNames = "users", sync = true)
        public User findSync(long id) {
            calls.incrementAndGet();
            return new User(id, "sync-" + id);
        }

        @Cacheable("nothing")
        public User findNothing(long id) {
            calls.incrementAndGet();
            return null;
        }

        @CacheEvict(cacheNames = "users", allEntries = true)
        public void evictAll() {
        }

        /** A method, not the field: callers hold the caching proxy, whose own fields are unset. */
        public int calls() {
            return calls.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class App {
        @Bean
        UserService userService() {
            return new UserService();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StratumAutoConfiguration.class,
                    StratumCacheManagerAutoConfiguration.class, StratumCacheMetricsAutoConfiguration.class))
            .withUserConfiguration(App.class)
            .withPropertyValues("stratum.providers.local.type=memory",
                    "stratum.cache-manager.endpoint=redis://localhost:6379");

    private static byte[] raw(Stratum stratum, String namespace, String key) {
        return stratum.provider("local").cache(CacheConfig.builder(URI.create("redis://localhost:6379"))
                .namespace(namespace).build(), Codec.bytes()).get(key).orElseThrow();
    }

    @Test
    void cacheableMethodRunsOncePerKey() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.find(1)).isEqualTo(new User(1, "user-1"));
            assertThat(service.find(1)).isEqualTo(new User(1, "user-1"));
            service.find(2);

            assertThat(service.calls()).isEqualTo(2);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(StratumCacheManager.class);
        });
    }

    @Test
    void syncCacheableLoadsThroughTheCache() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);
            service.findSync(7);
            service.findSync(7);

            assertThat(service.calls()).isEqualTo(1);
        });
    }

    @Test
    void nullResultsAreCached() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.findNothing(1)).isNull();
            assertThat(service.findNothing(1)).isNull();
            assertThat(service.calls()).isEqualTo(1);
        });
    }

    @Test
    void evictAllClearsTheNamespace() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);
            service.find(1);
            service.evictAll();
            service.find(1);

            assertThat(service.calls()).isEqualTo(2);
        });
    }

    @Test
    void perCacheTtlAndNamespacePrefix() {
        runner.withPropertyValues("stratum.cache-manager.namespace-prefix=orders-svc:",
                "stratum.cache-manager.default-ttl=10m", "stratum.cache-manager.ttl.users=1m").run(context -> {
            context.getBean(UserService.class).find(1);
            var users = (org.kamranzafar.stratum.cache.Cache<?>) context.getBean(CacheManager.class).getCache("users").getNativeCache();

            assertThat(users.namespace()).isEqualTo("orders-svc:users");
            assertThat(users.ttl("1")).hasValueSatisfying(ttl -> assertThat(ttl).isLessThanOrEqualTo(java.time.Duration.ofMinutes(1)));
        });
    }

    @Test
    void cacheNamesRestrictWhichCachesExist() {
        runner.withPropertyValues("stratum.cache-manager.cache-names=users").run(context -> {
            CacheManager manager = context.getBean(CacheManager.class);

            assertThat(manager.getCache("users")).isNotNull();
            assertThat(manager.getCache("other")).isNull();
            assertThat(manager.getCacheNames()).containsExactly("users");
        });
    }

    @Test
    void jsonCodecStoresReadableTypedJson() {
        runner.withPropertyValues("stratum.cache-manager.codec=json",
                "stratum.cache-manager.trusted-packages=org.kamranzafar.stratum.spring").run(context -> {
            UserService service = context.getBean(UserService.class);
            service.find(3);

            String stored = new String(raw(context.getBean(Stratum.class), "users", "3"), StandardCharsets.UTF_8);
            assertThat(stored).contains("\"@class\":\"" + User.class.getName() + "\"").contains("\"name\":\"user-3\"");
            assertThat(service.find(3)).isEqualTo(new User(3, "user-3"));
            assertThat(service.calls()).isEqualTo(1);
        });
    }

    @Test
    void jsonCodecCachesNulls() {
        runner.withPropertyValues("stratum.cache-manager.codec=json",
                "stratum.cache-manager.trusted-packages=org.kamranzafar.stratum.spring").run(context -> {
            UserService service = context.getBean(UserService.class);
            service.findNothing(1);
            service.findNothing(1);

            assertThat(service.calls()).isEqualTo(1);
        });
    }

    @Test
    void jsonCodecRequiresTrustedPackages() {
        runner.withPropertyValues("stratum.cache-manager.codec=json")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("trusted-packages is required"));
    }

    @Test
    void untrustedTypesAreNotDeserialized() {
        runner.withPropertyValues("stratum.cache-manager.trusted-packages=com.example.nothing").run(context -> {
            UserService service = context.getBean(UserService.class);
            service.find(1);

            assertThatThrownBy(() -> service.find(1)).hasRootCauseInstanceOf(java.io.InvalidClassException.class);
        });
    }

    @Test
    void applicationCacheManagerWins() {
        runner.withBean("cacheManager", CacheManager.class, ConcurrentMapCacheManager::new)
                .run(context -> assertThat(context.getBean(CacheManager.class)).isInstanceOf(ConcurrentMapCacheManager.class));
    }

    @Test
    void cacheManagerNeedsAnEndpoint() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StratumAutoConfiguration.class, StratumCacheManagerAutoConfiguration.class))
                .withPropertyValues("stratum.providers.local.type=memory")
                .run(context -> assertThat(context).doesNotHaveBean(StratumCacheManager.class));
    }

    @Test
    void parameterStoreReadsArePublishedAsMicrometerMeters() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("stratum.parameter-stores.config.kind=parameters",
                        "stratum.parameter-stores.config.cache-ttl=5m").run(context -> {
                    var store = context.getBean("config", org.kamranzafar.stratum.parameters.ParameterStore.class);
                    store.put("flag", "on");
                    store.get("flag");
                    store.get("flag");
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    assertThat(registry.get("stratum.parameters.reads").tags("store", "config", "source", "backend")
                            .functionCounter().count()).isEqualTo(1);
                    assertThat(registry.get("stratum.parameters.reads").tags("store", "config", "source", "cache")
                            .functionCounter().count()).isEqualTo(1);
                });
    }

    @Test
    void hitsAndMissesArePublishedAsMicrometerMeters() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("stratum.caches.sessions.endpoint=redis://localhost:6379").run(context -> {
                    UserService service = context.getBean(UserService.class);
                    service.find(1);
                    service.find(1);
                    @SuppressWarnings("unchecked")
                    var sessions = (org.kamranzafar.stratum.cache.Cache<String>) context.getBean("sessions", org.kamranzafar.stratum.cache.Cache.class);
                    sessions.get("missing");
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    assertThat(registry.get("cache.gets").tags("cache", "users", "result", "hit").functionCounter().count()).isEqualTo(1);
                    assertThat(registry.get("cache.gets").tags("cache", "users", "result", "miss").functionCounter().count()).isEqualTo(1);
                    assertThat(registry.get("cache.puts").tags("cache", "users").functionCounter().count()).isEqualTo(1);
                    assertThat(registry.get("cache.gets").tags("cache", "sessions", "result", "miss").functionCounter().count()).isEqualTo(1);
                    assertThat(registry.get("cache.loads").tags("cache", "users", "result", "success").functionCounter()).isNotNull();
                });
    }
}
