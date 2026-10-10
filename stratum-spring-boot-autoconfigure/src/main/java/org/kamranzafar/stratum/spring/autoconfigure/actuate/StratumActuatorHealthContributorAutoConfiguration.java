package org.kamranzafar.stratum.spring.autoconfigure.actuate;

import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Spring Boot 3 ({@code org.springframework.boot.actuate.health}): a {@code stratum} health
 * contributor with one entry per {@link BlobStore} bean. Boot 4 removed that package, so on Boot 4
 * this stays inactive and {@code health.StratumHealthContributorAutoConfiguration} applies instead.
 * Disable with {@code management.health.stratum.enabled=false}.
 */
@AutoConfiguration(after = StratumAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class, BlobStore.class})
@ConditionalOnBean(BlobStore.class)
@ConditionalOnEnabledHealthIndicator("stratum")
public class StratumActuatorHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "stratumHealthContributor")
    public HealthContributor stratumHealthContributor(Map<String, BlobStore> blobStores) {
        return CompositeHealthContributor.fromMap(blobStores, ActuatorBlobStoreHealthIndicator::new);
    }
}
