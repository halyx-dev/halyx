package org.kamranzafar.cirrus.spring.autoconfigure.actuate;

import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.spring.messaging.CirrusListenerRegistrar;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusAutoConfiguration;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusResourcesCondition;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.Map;
import java.util.TreeMap;

/**
 * Spring Boot 3 ({@code org.springframework.boot.actuate.health}): a {@code cirrus} health
 * contributor with one entry per {@link BlobStore} and {@link Cache} bean. Boot 4 removed that package, so on Boot 4
 * this stays inactive and {@code health.CirrusHealthContributorAutoConfiguration} applies instead.
 * Disable with {@code management.health.cirrus.enabled=false}.
 */
@AutoConfiguration(after = CirrusAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class, BlobStore.class})
@Conditional(CirrusResourcesCondition.class)
@ConditionalOnEnabledHealthIndicator("cirrus")
public class CirrusActuatorHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "cirrusHealthContributor")
    public HealthContributor cirrusHealthContributor(Map<String, BlobStore> blobStores, Map<String, Cache<?>> caches,
                                                      Map<String, ParameterStore> parameterStores,
                                                      Map<String, Subscription> subscriptions,
                                                      Map<String, DocumentStore> documentStores,
                                                      ObjectProvider<CirrusListenerRegistrar> listeners) {
        Map<String, HealthContributor> contributors = new TreeMap<>();
        blobStores.forEach((name, store) -> contributors.put(name, new ActuatorBlobStoreHealthIndicator(store)));
        caches.forEach((name, cache) -> contributors.put(name, new ActuatorCacheHealthIndicator(cache)));
        parameterStores.forEach((name, store) -> contributors.put(name, new ActuatorParameterStoreHealthIndicator(store)));
        documentStores.forEach((name, store) -> contributors.put(name, new ActuatorDocumentStoreHealthIndicator(store)));
        if (!subscriptions.isEmpty()) {
            listeners.ifAvailable(registrar -> contributors.put("messaging", new ActuatorMessageConsumersHealthIndicator(registrar)));
        }
        return CompositeHealthContributor.fromMap(contributors);
    }
}
