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
package dev.halyx;

import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.Codec;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnavailableException;
import dev.halyx.events.EventBusConfig;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudContextTest {

    private static final URI ENDPOINT = URI.create("redis://localhost:6379");

    @Test
    void equalConfigurationsShareOneClient() {
        try (Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build()) {
            CloudContext context = halyx.provider("local");

            assertThat(context.parameterStore(ParameterStoreConfig.secrets().prefix("app").build()))
                    .isSameAs(context.parameterStore(ParameterStoreConfig.secrets().prefix("app").build()))
                    .isNotSameAs(context.parameterStore(ParameterStoreConfig.secrets().prefix("other").build()));
            assertThat(context.cache(CacheConfig.builder(ENDPOINT).namespace("a").build()))
                    .isSameAs(context.cache(CacheConfig.builder(ENDPOINT).namespace("a").build()))
                    .isNotSameAs(context.cache(CacheConfig.builder(ENDPOINT).namespace("b").build()));
            assertThat(context.cache(CacheConfig.builder(ENDPOINT).namespace("a").build(), Codec.bytes()))
                    .isNotSameAs(context.cache(CacheConfig.builder(ENDPOINT).namespace("a").build()));
            assertThat(context.documentStore(TableConfig.builder("orders").build()))
                    .isSameAs(context.documentStore(TableConfig.builder("orders").build()));
            assertThat(context.publisher(TopicConfig.of("orders"))).isSameAs(context.publisher("orders"));
            assertThat(context.subscription(SubscriptionConfig.of("billing"))).isSameAs(context.subscription("billing"));
            assertThat(context.eventPublisher(EventBusConfig.of("bus"))).isSameAs(context.eventPublisher("bus"));
        }
    }

    @Test
    void aSharedParameterStoreSharesItsReadCache() {
        AtomicInteger reads = new AtomicInteger();
        try (Halyx halyx = Halyx.builder().plugin(provider(reads, null)).register("p", ProviderConfig.of("fake")).build()) {
            ParameterStoreConfig config = ParameterStoreConfig.parameters().cacheTtl(Duration.ofMinutes(5)).build();

            halyx.provider("p").parameterStore(config).get("db/url");
            halyx.provider("p").parameterStore(config).get("db/url");

            assertThat(reads).hasValue(1);
        }
    }

    @Test
    void parameterPingAlwaysReachesTheBackend() {
        AtomicInteger reads = new AtomicInteger();
        try (Halyx halyx = Halyx.builder().plugin(provider(reads, null)).register("p", ProviderConfig.of("fake")).build()) {
            ParameterStore store = halyx.provider("p").parameterStore(
                    ParameterStoreConfig.parameters().cacheTtl(Duration.ofMinutes(5)).build());

            store.ping();
            store.ping();

            assertThat(reads).hasValue(2);
        }
    }

    @Test
    void cachePingFailsOnAnUnavailableServerEvenWhenFailOpen() {
        UnavailableException down = new UnavailableException("connection refused");
        try (Halyx halyx = Halyx.builder().plugin(provider(new AtomicInteger(), down))
                .register("p", ProviderConfig.of("fake")).build()) {
            Cache<String> cache = halyx.provider("p").cache(CacheConfig.builder(ENDPOINT).failOpen(true).build());

            assertThat(cache.exists("k")).isFalse();
            assertThatThrownBy(cache::ping).isSameAs(down);
        }
    }

    /** A provider whose parameter reads are counted and whose cache server is {@code down} (or answers false). */
    private static CloudProvider provider(AtomicInteger parameterReads, CloudException down) {
        ParameterDriver parameters = (ParameterDriver) Proxy.newProxyInstance(CloudContextTest.class.getClassLoader(),
                new Class<?>[]{ParameterDriver.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> {
                        parameterReads.incrementAndGet();
                        yield Optional.empty();
                    }
                    case "capabilities" -> dev.halyx.capability.Capabilities.none();
                    case "translate" -> args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        CacheDriver cache = (CacheDriver) Proxy.newProxyInstance(CloudContextTest.class.getClassLoader(),
                new Class<?>[]{CacheDriver.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "exists" -> {
                        if (down != null) {
                            throw down;
                        }
                        yield false;
                    }
                    case "translate" -> args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ProviderConnection connection = new ProviderConnection() {
            @Override
            public ParameterDriver parameterStore(ParameterStoreConfig config) {
                return parameters;
            }

            @Override
            public CacheDriver cache(CacheConfig config) {
                return cache;
            }

            @Override
            public void close() {
            }
        };
        return new CloudProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public ProviderConnection connect(ProviderConfig config) {
                return connection;
            }
        };
    }
}
