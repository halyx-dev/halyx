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
package org.kamranzafar.cirrus.spring.autoconfigure.health;

import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.spring.messaging.CirrusListenerRegistrar;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusAutoConfiguration;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusResourcesCondition;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.springframework.beans.factory.ObjectProvider;
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
 * Spring Boot 4 ({@code org.springframework.boot.health}): a {@code cirrus} health contributor with
 * one entry per {@link BlobStore} and {@link Cache} bean. On Boot 3 these classes are absent and
 * {@code actuate.CirrusActuatorHealthContributorAutoConfiguration} applies instead. Disable with
 * {@code management.health.cirrus.enabled=false}.
 */
@AutoConfiguration(after = CirrusAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class, BlobStore.class})
@Conditional(CirrusResourcesCondition.class)
@ConditionalOnEnabledHealthIndicator("cirrus")
public class CirrusHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "cirrusHealthContributor")
    public HealthContributor cirrusHealthContributor(Map<String, BlobStore> blobStores, Map<String, Cache<?>> caches,
                                                      Map<String, ParameterStore> parameterStores,
                                                      Map<String, Subscription> subscriptions,
                                                      Map<String, DocumentStore> documentStores,
                                                      ObjectProvider<CirrusListenerRegistrar> listeners) {
        Map<String, HealthContributor> contributors = new TreeMap<>();
        blobStores.forEach((name, store) -> contributors.put(name, new BlobStoreHealthIndicator(store)));
        caches.forEach((name, cache) -> contributors.put(name, new CacheHealthIndicator(cache)));
        parameterStores.forEach((name, store) -> contributors.put(name, new ParameterStoreHealthIndicator(store)));
        documentStores.forEach((name, store) -> contributors.put(name, new DocumentStoreHealthIndicator(store)));
        if (!subscriptions.isEmpty()) {
            listeners.ifAvailable(registrar -> contributors.put("messaging", new MessageConsumersHealthIndicator(registrar)));
        }
        return CompositeHealthContributor.fromMap(contributors);
    }
}
