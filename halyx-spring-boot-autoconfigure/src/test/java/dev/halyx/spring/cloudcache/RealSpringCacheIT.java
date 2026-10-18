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
package dev.halyx.spring.cloudcache;

import dev.halyx.cache.Cache;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.cache.HalyxCacheManagerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with a real cloud's managed Redis or Valkey (tier 3), logging in with the cloud identity: {@code Cache}
 * beans from {@code halyx.caches}, of strings and of JSON objects, their health check, and Spring's
 * {@code @Cacheable} through {@code halyx.cache-manager}, on Boot 3.5 and 4. Runs only with
 * {@code -Dhalyx.real.spring.cache=true}, from the real-cloud scripts' tier 3, which pass the provider type and
 * properties, the endpoint ({@code halyx.real.spring.cache-endpoint}), the IAM user if the cloud needs one
 * ({@code .cache-username}) and the cache properties ({@code .cache-properties}, such as the ElastiCache name or the
 * private CA). Keys live in a namespace of their own, cleared at the end.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.cache", matches = "true")
class RealSpringCacheIT {

    static final String RUN = "spring-" + UUID.randomUUID().toString().substring(0, 8);

    public record Quote(String symbol, long cents) implements Serializable {
    }

    public static class Quotes {
        final AtomicInteger calls = new AtomicInteger();

        @Cacheable("quotes")
        public Quote quote(String symbol) {
            calls.incrementAndGet();
            return new Quote(symbol, 12_345);
        }

        public int calls() {
            return calls.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class App {
        @Bean // not "quotes": that is the halyx.caches bean's name, and bean names are shared
        Quotes quoteService() {
            return new Quotes();
        }
    }

    private static void addAll(List<String> properties, String prefix, String pairs) {
        for (String pair : pairs.split(",")) {
            if (!pair.isBlank()) {
                properties.add(prefix + pair.trim());
            }
        }
    }

    /** The cache's connection and login under {@code prefix}: {@code halyx.caches.<name>} or {@code halyx.cache-manager}. */
    private static void connection(List<String> properties, String prefix) {
        properties.add(prefix + ".endpoint=" + System.getProperty("halyx.real.spring.cache-endpoint"));
        properties.add(prefix + ".auth.type=iam");
        String username = System.getProperty("halyx.real.spring.cache-username", "");
        if (!username.isBlank()) {
            properties.add(prefix + ".auth.username=" + username);
        }
        addAll(properties, prefix + ".properties.", System.getProperty("halyx.real.spring.cache-properties", ""));
    }

    private ApplicationContextRunner runner() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.caches.sessions.namespace=" + RUN + "-sessions",
                "halyx.caches.quotes.namespace=" + RUN + "-quotes",
                "halyx.caches.quotes.codec=json",
                "halyx.caches.quotes.trusted-packages=dev.halyx.spring.cloudcache",
                "halyx.cache-manager.namespace-prefix=" + RUN + ":",
                "halyx.cache-manager.codec=json",
                "halyx.cache-manager.trusted-packages=dev.halyx.spring.cloudcache"));
        addAll(properties, "halyx.providers.cloud.properties.", System.getProperty("halyx.real.spring.provider-properties", ""));
        connection(properties, "halyx.caches.sessions");
        connection(properties, "halyx.caches.quotes");
        connection(properties, "halyx.cache-manager");
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class,
                        HalyxCacheManagerAutoConfiguration.class))
                .withUserConfiguration(App.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheBeansAndCacheableLogInWithTheCloudIdentity() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Cache<String> sessions = context.getBean("sessions", Cache.class);
            Cache<Object> quotes = context.getBean("quotes", Cache.class);
            try {
                sessions.ping();
                sessions.put("s-1", "alice");
                assertThat(sessions.get("s-1")).hasValue("alice");
                sessions.async().put("s-2", "bob").get(60, TimeUnit.SECONDS);
                assertThat(sessions.async().get("s-2").get(60, TimeUnit.SECONDS)).hasValue("bob");
                assertThat(sessions.async().getOrLoad("s-3", () -> CompletableFuture.completedFuture("carol"))
                        .get(60, TimeUnit.SECONDS)).isEqualTo("carol");
                assertThat(sessions.get("s-3")).as("loaded asynchronously, read blocking").hasValue("carol");

                Quote quote = new Quote("ACME", 4_200);
                quotes.put("ACME", quote);
                assertThat(quotes.get("ACME")).hasValue(quote);

                Quotes service = context.getBean(Quotes.class);
                assertThat(service.quote("HLX")).isEqualTo(new Quote("HLX", 12_345));
                assertThat(service.quote("HLX")).isEqualTo(new Quote("HLX", 12_345));
                assertThat(service.calls()).isEqualTo(1);
            } finally {
                sessions.clear();
                quotes.clear();
                context.getBean(org.springframework.cache.CacheManager.class).getCache("quotes").clear();
            }
        });
    }
}
