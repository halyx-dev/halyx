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

import dev.halyx.nosql.DocumentStore;
import dev.halyx.spring.data.document.DocumentTable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

import java.util.Arrays;

/**
 * Creates a repository bean. Each entity's document store is the {@code DocumentStore} bean named by its
 * {@link DocumentTable}, or the application's only {@code DocumentStore} bean.
 */
public class HalyxRepositoryFactoryBean<R extends Repository<T, ID>, T, ID>
        extends RepositoryFactoryBeanSupport<R, T, ID> {

    private ListableBeanFactory beans;

    public HalyxRepositoryFactoryBean(Class<? extends R> repositoryInterface) {
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
        return new HalyxRepositoryFactory(this::store);
    }

    private DocumentStore store(Class<?> entity) {
        DocumentTable table = entity.getAnnotation(DocumentTable.class);
        String[] names = beans.getBeanNamesForType(DocumentStore.class);
        if (table != null) {
            if (!Arrays.asList(names).contains(table.value())) {
                throw new IllegalStateException(entity.getName() + " is stored in document store '" + table.value()
                        + "', but there is no DocumentStore bean of that name. DocumentStore beans: "
                        + Arrays.toString(names));
            }
            return beans.getBean(table.value(), DocumentStore.class);
        }
        if (names.length != 1) {
            throw new IllegalStateException(entity.getName() + " needs @DocumentTable(\"<bean name>\"): there are "
                    + names.length + " DocumentStore beans " + Arrays.toString(names));
        }
        return beans.getBean(names[0], DocumentStore.class);
    }
}
