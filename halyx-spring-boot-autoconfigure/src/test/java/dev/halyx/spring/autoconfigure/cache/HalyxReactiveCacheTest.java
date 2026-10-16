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
package dev.halyx.spring.autoconfigure.cache;

import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @Cacheable} and {@code @CacheEvict} on methods returning {@code CompletableFuture}, {@code Mono} and
 * {@code Flux}, which Spring serves through {@code Cache.retrieve}; on Boot 3.5 and 4.
 */
class HalyxReactiveCacheTest {

    public record User(long id, String name) implements Serializable {
    }

    public static class UserService {
        final AtomicInteger calls = new AtomicInteger();

        @Cacheable("users")
        public CompletableFuture<User> future(long id) {
            calls.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> new User(id, "future-" + id));
        }

        @Cacheable("users")
        public Mono<User> mono(long id) {
            return Mono.fromCallable(() -> {
                calls.incrementAndGet();
                return new User(id, "mono-" + id);
            });
        }

        /** Emits on a Reactor non-blocking thread, where Spring then writes the result to the cache. */
        @Cacheable("users")
        public Mono<User> monoOnParallel(long id) {
            return Mono.fromCallable(() -> {
                calls.incrementAndGet();
                return new User(id, "parallel-" + id);
            }).subscribeOn(Schedulers.parallel());
        }

        @Cacheable(cacheNames = "users", sync = true)
        public Mono<User> monoSync(long id) {
            return Mono.fromCallable(() -> {
                calls.incrementAndGet();
                return new User(id, "sync-" + id);
            });
        }

        @Cacheable("lists")
        public Flux<User> flux(long id) {
            return Flux.defer(() -> {
                calls.incrementAndGet();
                return Flux.just(new User(id, "a"), new User(id, "b"));
            });
        }

        @Cacheable(cacheNames = "users", sync = true)
        public Mono<User> failing(long id) {
            return Mono.error(new IllegalStateException("load failed"));
        }

        @CacheEvict(cacheNames = "users", key = "#id")
        public Mono<Void> evict(long id) {
            return Mono.empty();
        }

        public int calls() {
            return calls.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class App {
        static final Set<String> executorThreads = ConcurrentHashMap.newKeySet();

        @Bean
        UserService userService() {
            return new UserService();
        }

        @Bean(HalyxCacheManagerAutoConfiguration.EXECUTOR_BEAN)
        Executor halyxCacheExecutor() {
            return task -> Thread.ofVirtual().name("app-cache-executor").start(() -> {
                executorThreads.add(Thread.currentThread().getName());
                task.run();
            });
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxCacheManagerAutoConfiguration.class))
            .withUserConfiguration(App.class)
            .withPropertyValues("halyx.providers.local.type=memory",
                    "halyx.cache-manager.endpoint=redis://localhost:6379");

    @Test
    void completableFutureResultsAreCached() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.future(1).join()).isEqualTo(new User(1, "future-1"));
            assertThat(service.future(1).join()).isEqualTo(new User(1, "future-1"));

            assertThat(service.calls()).isEqualTo(1);
            assertThat(App.executorThreads).as("the app's executor ran the cache calls").contains("app-cache-executor");
        });
    }

    @Test
    void monoResultsAreCachedAndEvicted() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.mono(2).block(Duration.ofSeconds(5))).isEqualTo(new User(2, "mono-2"));
            assertThat(service.mono(2).block(Duration.ofSeconds(5))).isEqualTo(new User(2, "mono-2"));
            assertThat(service.calls()).isEqualTo(1);

            service.evict(2).block(Duration.ofSeconds(5));
            assertThat(service.mono(2).block(Duration.ofSeconds(5))).isEqualTo(new User(2, "mono-2"));
            assertThat(service.calls()).isEqualTo(2);
        });
    }

    @Test
    void writesFromANonBlockingThreadDoNotBlockIt() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.monoOnParallel(3).block(Duration.ofSeconds(5))).isEqualTo(new User(3, "parallel-3"));
            // the write was handed to the executor; wait for it to land
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            User cached = null;
            while (cached == null && System.nanoTime() < deadline) {
                cached = (User) context.getBean(HalyxCacheManager.class).getCache("users").get(3L, User.class);
                Thread.sleep(20);
            }

            assertThat(cached).isEqualTo(new User(3, "parallel-3"));
            assertThat(service.monoOnParallel(3).block(Duration.ofSeconds(5))).isEqualTo(new User(3, "parallel-3"));
            assertThat(service.calls()).isEqualTo(1);
        });
    }

    @Test
    void syncMonoLoadsOnceAndFailuresPropagate() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            List<User> users = Flux.range(0, 8).flatMap(i -> service.monoSync(4)).collectList().block(Duration.ofSeconds(5));

            assertThat(users).hasSize(8).containsOnly(new User(4, "sync-4"));
            assertThat(service.calls()).isEqualTo(1);
            assertThatThrownBy(() -> service.failing(5).block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("load failed");
        });
    }

    @Test
    void fluxResultsAreCachedAsAList() {
        runner.run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.flux(6).collectList().block(Duration.ofSeconds(5))).extracting(User::name).containsExactly("a", "b");
            assertThat(service.flux(6).collectList().block(Duration.ofSeconds(5))).extracting(User::name).containsExactly("a", "b");

            assertThat(service.calls()).isEqualTo(1);
        });
    }
}
