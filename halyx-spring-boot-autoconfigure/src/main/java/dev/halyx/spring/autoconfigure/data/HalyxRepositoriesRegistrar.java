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
package dev.halyx.spring.autoconfigure.data;

import dev.halyx.spring.data.document.EnableHalyxRepositories;
import dev.halyx.spring.data.document.repository.HalyxRepositoryConfigurationExtension;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource;
import org.springframework.data.repository.config.RepositoryConfigurationDelegate;
import org.springframework.data.util.Streamable;

/**
 * Registers the repositories in the application's auto-configuration packages, as {@code @EnableHalyxRepositories}
 * on the application class would. Uses Spring Data's own configuration classes, the same on Boot 3.5 and 4.
 */
class HalyxRepositoriesRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, ResourceLoaderAware,
        EnvironmentAware {

    private BeanFactory beanFactory;
    private ResourceLoader resourceLoader;
    private Environment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry,
                                        BeanNameGenerator generator) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return;
        }
        var source = new AnnotationRepositoryConfigurationSource(
                AnnotationMetadata.introspect(EnableHalyxRepositoriesConfiguration.class), EnableHalyxRepositories.class,
                resourceLoader, environment, registry, generator) {
            @Override
            public Streamable<String> getBasePackages() {
                return Streamable.of(AutoConfigurationPackages.get(beanFactory));
            }
        };
        new RepositoryConfigurationDelegate(source, resourceLoader, environment)
                .registerRepositoriesIn(registry, new HalyxRepositoryConfigurationExtension());
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        registerBeanDefinitions(metadata, registry, null);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @EnableHalyxRepositories
    private static final class EnableHalyxRepositoriesConfiguration {
    }
}
