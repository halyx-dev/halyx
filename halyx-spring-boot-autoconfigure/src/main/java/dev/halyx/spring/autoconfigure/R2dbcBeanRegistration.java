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

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.util.Map;
import java.util.Set;

/**
 * Registers a {@code ConnectionFactory} bean for each {@code halyx.r2dbc-databases.<name>} entry, named
 * {@code <name>}, primary when it is the only one (or marked {@code primary}), so Spring Boot's own R2DBC
 * auto-configuration backs off and Spring Data R2DBC uses it. Called only when {@code halyx-r2dbc} is present.
 */
final class R2dbcBeanRegistration {

    private R2dbcBeanRegistration() {
    }

    static void register(Binder binder, BeanDefinitionRegistry registry, HalyxBeanNames names,
                         Set<String> parameterStores) {
        Map<String, HalyxProperties.DatabaseProperties> databases = binder
                .bind(HalyxR2dbcDatabases.GROUP, Bindable.mapOf(String.class, HalyxProperties.DatabaseProperties.class))
                .orElse(Map.of());
        if (databases.isEmpty()) {
            return;
        }
        if (!registry.containsBeanDefinition(HalyxR2dbcDatabases.BEAN)) {
            RootBeanDefinition factory = new RootBeanDefinition(HalyxR2dbcDatabases.class);
            factory.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            registry.registerBeanDefinition(HalyxR2dbcDatabases.BEAN, factory);
        }
        databases.forEach((name, database) -> {
            String property = HalyxR2dbcDatabases.GROUP + "." + name;
            RootBeanDefinition definition = new RootBeanDefinition(ConnectionFactory.class);
            definition.setFactoryBeanName(HalyxR2dbcDatabases.BEAN);
            var args = definition.getConstructorArgumentValues();
            args.addIndexedArgumentValue(0, property);
            args.addIndexedArgumentValue(1, new ConstructorArgumentValues.ValueHolder(database.provider(),
                    String.class.getName()));
            args.addIndexedArgumentValue(2, name);
            args.addIndexedArgumentValue(3, database);
            var secret = database.auth().secret();
            if (secret != null) {
                if (secret.store() == null || !parameterStores.contains(secret.store())) {
                    throw new IllegalStateException(property + ".auth.secret.store must name one of halyx.parameter-stores "
                            + parameterStores);
                }
                definition.setFactoryMethodName("connectionFactoryWithSecret");
                args.addIndexedArgumentValue(4, new RuntimeBeanReference(secret.store()));
            } else {
                definition.setFactoryMethodName("connectionFactory");
            }
            definition.setTargetType(ConnectionFactory.class);
            definition.setDestroyMethodName("dispose");
            definition.setPrimary(databases.size() == 1 || database.primary());
            definition.setDescription("Halyx R2DBC " + database.engine() + " database " + name + "/"
                    + database.database());
            names.register(name, property, null, definition);
        });
    }
}
