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
package dev.halyx.spring.data.document.repository;

import dev.halyx.reactor.ReactiveHalyx;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

/**
 * Creates a reactive repository bean. Each entity's document store is found as for blocking repositories; the
 * calls run on the application's {@code ReactiveHalyx} bean.
 */
public class HalyxReactiveRepositoryFactoryBean<R extends Repository<T, ID>, T, ID>
        extends RepositoryFactoryBeanSupport<R, T, ID> {

    private ListableBeanFactory beans;

    public HalyxReactiveRepositoryFactoryBean(Class<? extends R> repositoryInterface) {
        super(repositoryInterface);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        super.setBeanFactory(beanFactory);
        if (!(beanFactory instanceof ListableBeanFactory listable)) {
            throw new IllegalStateException("Halyx repositories need a ListableBeanFactory");
        }
        this.beans = listable;
    }

    @Override
    protected RepositoryFactorySupport createRepositoryFactory() {
        ReactiveHalyx reactive = beans.getBeanProvider(ReactiveHalyx.class).getIfAvailable(() -> {
            throw new IllegalStateException("Reactive Halyx repositories need a ReactiveHalyx bean: Spring Boot "
                    + "makes one with halyx-reactor on the classpath; otherwise declare ReactiveHalyx.of(halyx, scheduler)");
        });
        return new HalyxReactiveRepositoryFactory(entity -> DocumentStores.forEntity(beans, entity), reactive);
    }
}
