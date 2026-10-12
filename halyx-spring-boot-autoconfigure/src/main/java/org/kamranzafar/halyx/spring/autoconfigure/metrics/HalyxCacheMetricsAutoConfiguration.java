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
package org.kamranzafar.halyx.spring.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.kamranzafar.halyx.cache.Cache;
import org.kamranzafar.halyx.messaging.ConsumerStatistics;
import org.kamranzafar.halyx.parameters.ParameterStore;
import org.kamranzafar.halyx.spring.messaging.HalyxListenerRegistrar;
import org.kamranzafar.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.kamranzafar.halyx.spring.autoconfigure.cache.HalyxCacheManager;
import org.kamranzafar.halyx.spring.autoconfigure.cache.HalyxCacheManagerAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Binds every {@code Cache} bean and every cache of the {@link HalyxCacheManager} to the
 * application's {@link MeterRegistry}, tagged {@code cache=<name>}, and parameter store read counters.
 */
@AutoConfiguration(after = {HalyxAutoConfiguration.class, HalyxCacheManagerAutoConfiguration.class}, afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration" // Boot 4
})
@ConditionalOnClass({MeterRegistry.class, Cache.class})
@ConditionalOnBean(MeterRegistry.class)
public class HalyxCacheMetricsAutoConfiguration {

    /**
     * {@code halyx.parameters.reads{store, source=cache|backend}}, {@code halyx.parameters.changes{store}}
     * and {@code halyx.parameters.watch.failures{store}} for every {@code ParameterStore} bean.
     */
    @Bean
    public SmartInitializingSingleton halyxParameterMetricsBinder(MeterRegistry registry,
                                                                    ObjectProvider<Map<String, ParameterStore>> stores) {
        return () -> stores.ifAvailable(beans -> beans.forEach((name, store) -> {
            FunctionCounter.builder("halyx.parameters.reads", store, s -> s.statistics().cacheHits())
                    .tag("store", name).tag("source", "cache").register(registry);
            FunctionCounter.builder("halyx.parameters.reads", store, s -> s.statistics().backendReads())
                    .tag("store", name).tag("source", "backend").register(registry);
            FunctionCounter.builder("halyx.parameters.changes", store, s -> s.statistics().changesDetected())
                    .tag("store", name).register(registry);
            FunctionCounter.builder("halyx.parameters.watch.failures", store, s -> s.statistics().watchFailures())
                    .tag("store", name).register(registry);
        }));
    }

    /**
     * {@code halyx.messaging.messages{subscription, listener, result=received|acked|nacked|failed}},
     * {@code halyx.messaging.receive.errors} and the {@code halyx.messaging.in.flight} gauge for every
     * {@code @HalyxListener} consumer.
     */
    @Bean
    public SmartInitializingSingleton halyxMessagingMetricsBinder(MeterRegistry registry,
                                                                    ObjectProvider<HalyxListenerRegistrar> listeners) {
        return () -> listeners.ifAvailable(registrar -> registrar.subscriptionKeys().forEach(key -> {
            String subscription = key.substring(0, key.indexOf('#'));
            String listener = key.substring(key.indexOf('#') + 1);
            Map<String, ToDoubleFunction<ConsumerStatistics>> counters = Map.of(
                    "received", ConsumerStatistics::received, "acked", ConsumerStatistics::acked,
                    "nacked", ConsumerStatistics::nacked, "failed", ConsumerStatistics::failed);
            counters.forEach((result, value) -> FunctionCounter.builder("halyx.messaging.messages", registrar,
                            r -> stats(r, key).map(value::applyAsDouble).orElse(0.0))
                    .tag("subscription", subscription).tag("listener", listener).tag("result", result)
                    .register(registry));
            FunctionCounter.builder("halyx.messaging.receive.errors", registrar,
                            r -> stats(r, key).map(s -> (double) s.receiveErrors()).orElse(0.0))
                    .tag("subscription", subscription).tag("listener", listener).register(registry);
            Gauge.builder("halyx.messaging.in.flight", registrar, r -> stats(r, key).map(s -> (double) s.inFlight()).orElse(0.0))
                    .tag("subscription", subscription).tag("listener", listener).register(registry);
        }));
    }

    private static Optional<ConsumerStatistics> stats(HalyxListenerRegistrar registrar, String key) {
        return Optional.ofNullable(registrar.consumers().get(key)).map(c -> c.statistics());
    }

    @Bean
    public SmartInitializingSingleton halyxCacheMetricsBinder(MeterRegistry registry,
                                                                ObjectProvider<Map<String, Cache<?>>> caches,
                                                                ObjectProvider<HalyxCacheManager> cacheManager) {
        return () -> {
            caches.ifAvailable(beans -> beans.forEach((name, cache) ->
                    new HalyxCacheMeterBinder(cache, name, Tags.empty()).bindTo(registry)));
            cacheManager.ifAvailable(manager -> manager.onCacheCreated((name, cache) ->
                    new HalyxCacheMeterBinder(cache, name, Tags.of("cache.manager", "halyxCacheManager")).bindTo(registry)));
        };
    }
}
