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
package dev.halyx.spring.autoconfigure;

import dev.halyx.cache.Cache;
import dev.halyx.cache.Codec;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;

/**
 * Gives each {@code halyx.caches} bean whose codec is a {@code Codec} bean of the application's the codec's value
 * type, {@code Cache<V>} for a {@code Codec<V>}, so it injects by type. The codec bean's type is known only once
 * every bean definition is registered, so this runs then, before any bean is created.
 */
final class CacheCodecTypes implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    /** On a cache bean definition: the name of the {@code Codec} bean that encodes its values. */
    static final String CODEC_BEAN = CacheCodecTypes.class.getName() + ".codec";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        boolean changed = false;
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            if (!(definition.getAttribute(CODEC_BEAN) instanceof String codec) || !(definition instanceof RootBeanDefinition cache)
                    || !beanFactory.containsBean(codec) || !beanFactory.isTypeMatch(codec, Codec.class)) {
                continue;
            }
            ResolvableType value = codecType(beanFactory, codec).as(Codec.class).getGeneric(0);
            if (value.resolve() != null) {
                cache.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, value));
                changed = true;
            }
        }
        if (changed) {
            beanFactory.clearMetadataCache();
        }
    }

    /** The codec bean's full type, generics included, as its factory method or class declares it. */
    private static ResolvableType codecType(ConfigurableListableBeanFactory beanFactory, String codec) {
        if (beanFactory.getMergedBeanDefinition(codec) instanceof RootBeanDefinition definition) {
            ResolvableType type = definition.getResolvableType();
            if (type.resolve() != null && type.as(Codec.class).getGeneric(0).resolve() != null) {
                return type;
            }
        }
        Class<?> type = beanFactory.getType(codec, false);
        return type == null ? ResolvableType.NONE : ResolvableType.forClass(type);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
