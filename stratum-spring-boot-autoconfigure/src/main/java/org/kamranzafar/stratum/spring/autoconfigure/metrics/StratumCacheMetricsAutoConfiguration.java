package org.kamranzafar.stratum.spring.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.spring.autoconfigure.cache.StratumCacheManager;
import org.kamranzafar.stratum.spring.autoconfigure.cache.StratumCacheManagerAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Binds every {@code Cache} bean and every cache of the {@link StratumCacheManager} to the
 * application's {@link MeterRegistry}, tagged {@code cache=<name>}, and parameter store read counters.
 */
@AutoConfiguration(after = {StratumAutoConfiguration.class, StratumCacheManagerAutoConfiguration.class}, afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration" // Boot 4
})
@ConditionalOnClass({MeterRegistry.class, Cache.class})
@ConditionalOnBean(MeterRegistry.class)
public class StratumCacheMetricsAutoConfiguration {

    /**
     * {@code stratum.parameters.reads{store, source=cache|backend}}, {@code stratum.parameters.changes{store}}
     * and {@code stratum.parameters.watch.failures{store}} for every {@code ParameterStore} bean.
     */
    @Bean
    public SmartInitializingSingleton stratumParameterMetricsBinder(MeterRegistry registry,
                                                                    ObjectProvider<Map<String, ParameterStore>> stores) {
        return () -> stores.ifAvailable(beans -> beans.forEach((name, store) -> {
            FunctionCounter.builder("stratum.parameters.reads", store, s -> s.statistics().cacheHits())
                    .tag("store", name).tag("source", "cache").register(registry);
            FunctionCounter.builder("stratum.parameters.reads", store, s -> s.statistics().backendReads())
                    .tag("store", name).tag("source", "backend").register(registry);
            FunctionCounter.builder("stratum.parameters.changes", store, s -> s.statistics().changesDetected())
                    .tag("store", name).register(registry);
            FunctionCounter.builder("stratum.parameters.watch.failures", store, s -> s.statistics().watchFailures())
                    .tag("store", name).register(registry);
        }));
    }

    @Bean
    public SmartInitializingSingleton stratumCacheMetricsBinder(MeterRegistry registry,
                                                                ObjectProvider<Map<String, Cache<?>>> caches,
                                                                ObjectProvider<StratumCacheManager> cacheManager) {
        return () -> {
            caches.ifAvailable(beans -> beans.forEach((name, cache) ->
                    new StratumCacheMeterBinder(cache, name, Tags.empty()).bindTo(registry)));
            cacheManager.ifAvailable(manager -> manager.onCacheCreated((name, cache) ->
                    new StratumCacheMeterBinder(cache, name, Tags.of("cache.manager", "stratumCacheManager")).bindTo(registry)));
        };
    }
}
