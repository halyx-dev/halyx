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
package dev.halyx.spring.autoconfigure.reactor;

import dev.halyx.cache.Cache;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.reactor.ReactiveBlobStore;
import dev.halyx.reactor.ReactiveCache;
import dev.halyx.reactor.ReactiveDocumentStore;
import dev.halyx.reactor.ReactiveEventPublisher;
import dev.halyx.reactor.ReactiveParameterStore;
import dev.halyx.reactor.ReactivePublisher;
import dev.halyx.reactor.ReactiveStreamConsumer;
import dev.halyx.reactor.ReactiveStreamProducer;
import dev.halyx.reactor.ReactiveSubscription;
import dev.halyx.spring.autoconfigure.HalyxBlobStores;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers a reactive view of every service bean made from {@code halyx.*} properties: {@code <name>Reactive},
 * qualified {@code <name>} so {@code @Qualifier("<name>")} finds it by its reactive type, made by
 * {@code ReactiveHalyx.wrap} over the blocking bean.
 */
final class ReactiveServiceBeans implements BeanDefinitionRegistryPostProcessor {

    private static final Map<Class<?>, ResolvableType> REACTIVE_TYPES = new LinkedHashMap<>();

    static {
        REACTIVE_TYPES.put(BlobStore.class, ResolvableType.forClass(ReactiveBlobStore.class));
        REACTIVE_TYPES.put(DocumentStore.class, ResolvableType.forClass(ReactiveDocumentStore.class));
        REACTIVE_TYPES.put(Publisher.class, ResolvableType.forClass(ReactivePublisher.class));
        REACTIVE_TYPES.put(Subscription.class, ResolvableType.forClass(ReactiveSubscription.class));
        REACTIVE_TYPES.put(EventPublisher.class, ResolvableType.forClass(ReactiveEventPublisher.class));
        REACTIVE_TYPES.put(StreamProducer.class, ResolvableType.forClass(ReactiveStreamProducer.class));
        REACTIVE_TYPES.put(StreamConsumer.class, ResolvableType.forClass(ReactiveStreamConsumer.class));
        REACTIVE_TYPES.put(ParameterStore.class, ResolvableType.forClass(ReactiveParameterStore.class));
        REACTIVE_TYPES.put(Cache.class, ResolvableType.forClassWithGenerics(ReactiveCache.class, String.class));
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition service = registry.getBeanDefinition(name);
            if (!HalyxBlobStores.BEAN_NAME.equals(service.getFactoryBeanName())
                    || !(service instanceof AbstractBeanDefinition definition)) {
                continue;
            }
            Class<?> type = definition.getResolvableType().resolve();
            ResolvableType reactive = type == null ? null : REACTIVE_TYPES.get(type);
            String reactiveName = name + "Reactive";
            if (reactive == null || registry.containsBeanDefinition(reactiveName)) {
                continue;
            }
            RootBeanDefinition view = new RootBeanDefinition();
            view.setFactoryBeanName(HalyxReactorAutoConfiguration.REACTIVE_HALYX_BEAN);
            view.setFactoryMethodName("wrap");
            view.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference(name));
            view.setTargetType(reactive);
            view.addQualifier(new AutowireCandidateQualifier(Qualifier.class, name));
            view.setDescription("Reactive view of " + name);
            registry.registerBeanDefinition(reactiveName, view);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }
}
