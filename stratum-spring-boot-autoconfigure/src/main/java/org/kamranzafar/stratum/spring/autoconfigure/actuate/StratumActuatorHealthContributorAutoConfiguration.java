package org.kamranzafar.stratum.spring.autoconfigure.actuate;

import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.spring.autoconfigure.StratumResourcesCondition;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.Map;
import java.util.TreeMap;

/**
 * Spring Boot 3 ({@code org.springframework.boot.actuate.health}): a {@code stratum} health
 * contributor with one entry per {@link BlobStore} and {@link Cache} bean. Boot 4 removed that package, so on Boot 4
 * this stays inactive and {@code health.StratumHealthContributorAutoConfiguration} applies instead.
 * Disable with {@code management.health.stratum.enabled=false}.
 */
@AutoConfiguration(after = StratumAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class, BlobStore.class})
@Conditional(StratumResourcesCondition.class)
@ConditionalOnEnabledHealthIndicator("stratum")
public class StratumActuatorHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "stratumHealthContributor")
    public HealthContributor stratumHealthContributor(Map<String, BlobStore> blobStores, Map<String, Cache<?>> caches,
                                                      Map<String, ParameterStore> parameterStores) {
        Map<String, HealthContributor> contributors = new TreeMap<>();
        blobStores.forEach((name, store) -> contributors.put(name, new ActuatorBlobStoreHealthIndicator(store)));
        caches.forEach((name, cache) -> contributors.put(name, new ActuatorCacheHealthIndicator(cache)));
        parameterStores.forEach((name, store) -> contributors.put(name, new ActuatorParameterStoreHealthIndicator(store)));
        return CompositeHealthContributor.fromMap(contributors);
    }
}
