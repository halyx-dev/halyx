package org.kamranzafar.stratum.spring.autoconfigure.health;

import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.spring.autoconfigure.StratumResourcesCondition;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.Map;
import java.util.TreeMap;

/**
 * Spring Boot 4 ({@code org.springframework.boot.health}): a {@code stratum} health contributor with
 * one entry per {@link BlobStore} and {@link Cache} bean. On Boot 3 these classes are absent and
 * {@code actuate.StratumActuatorHealthContributorAutoConfiguration} applies instead. Disable with
 * {@code management.health.stratum.enabled=false}.
 */
@AutoConfiguration(after = StratumAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class, BlobStore.class})
@Conditional(StratumResourcesCondition.class)
@ConditionalOnEnabledHealthIndicator("stratum")
public class StratumHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "stratumHealthContributor")
    public HealthContributor stratumHealthContributor(Map<String, BlobStore> blobStores, Map<String, Cache<?>> caches) {
        Map<String, HealthContributor> contributors = new TreeMap<>();
        blobStores.forEach((name, store) -> contributors.put(name, new BlobStoreHealthIndicator(store)));
        caches.forEach((name, cache) -> contributors.put(name, new CacheHealthIndicator(cache)));
        return CompositeHealthContributor.fromMap(contributors);
    }
}
