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
package dev.halyx.spring.autoconfigure.actuate;

import dev.halyx.cache.Cache;
import dev.halyx.messaging.Subscription;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.spring.messaging.HalyxListenerRegistrar;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.HalyxResourcesCondition;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
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
 * Spring Boot 3 ({@code org.springframework.boot.actuate.health}): a {@code halyx} health
 * contributor with one entry per blob store, cache, parameter store, document store and stream producer bean,
 * plus the {@code messaging} and {@code streams} listeners. Boot 4 removed that package, so on Boot 4
 * this stays inactive and {@code health.HalyxHealthContributorAutoConfiguration} applies instead.
 * Disable with {@code management.health.halyx.enabled=false}.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class, BlobStore.class})
@Conditional(HalyxResourcesCondition.class)
@ConditionalOnEnabledHealthIndicator("halyx")
public class HalyxActuatorHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "halyxHealthContributor")
    public HealthContributor halyxHealthContributor(Map<String, BlobStore> blobStores, Map<String, Cache<?>> caches,
                                                      Map<String, ParameterStore> parameterStores,
                                                      Map<String, Subscription> subscriptions,
                                                      Map<String, DocumentStore> documentStores,
                                                      Map<String, StreamProducer> streamProducers,
                                                      Map<String, StreamConsumer> streamConsumers,
                                                      ObjectProvider<HalyxListenerRegistrar> listeners) {
        Map<String, HealthContributor> contributors = new TreeMap<>();
        blobStores.forEach((name, store) -> contributors.put(name, new ActuatorBlobStoreHealthIndicator(store)));
        caches.forEach((name, cache) -> contributors.put(name, new ActuatorCacheHealthIndicator(cache)));
        parameterStores.forEach((name, store) -> contributors.put(name, new ActuatorParameterStoreHealthIndicator(store)));
        documentStores.forEach((name, store) -> contributors.put(name, new ActuatorDocumentStoreHealthIndicator(store)));
        streamProducers.forEach((name, producer) -> contributors.put(name, new ActuatorStreamProducerHealthIndicator(producer)));
        if (!streamConsumers.isEmpty()) {
            listeners.ifAvailable(registrar -> putListenerEntry(contributors, "streams", new ActuatorStreamListenersHealthIndicator(registrar)));
        }
        if (!subscriptions.isEmpty()) {
            listeners.ifAvailable(registrar -> putListenerEntry(contributors, "messaging", new ActuatorMessageConsumersHealthIndicator(registrar)));
        }
        return CompositeHealthContributor.fromMap(contributors);
    }

    /** The listener entries share the contributor with the beans, so a bean of the same name would hide one. */
    private static void putListenerEntry(Map<String, HealthContributor> contributors, String name,
                                         HealthContributor indicator) {
        if (contributors.containsKey(name)) {
            throw new IllegalStateException("The Halyx bean '" + name + "' clashes with the halyx health entry of the "
                    + "same name, which reports the " + name + " listeners: rename the bean");
        }
        contributors.put(name, indicator);
    }
}
