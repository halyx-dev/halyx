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

import dev.halyx.spring.data.document.ReactiveDocumentRepository;
import dev.halyx.spring.data.document.DocumentTable;

import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.core.RepositoryMetadata;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

/** Spring Data's configuration for reactive Halyx repositories: which interfaces and entities are Halyx'. */
public class HalyxReactiveRepositoryConfigurationExtension extends RepositoryConfigurationExtensionSupport {

    @Override
    public String getModuleName() {
        return "Halyx Reactive";
    }

    @Override
    protected String getModulePrefix() {
        return "halyx";
    }

    @Override
    public String getRepositoryFactoryBeanClassName() {
        return HalyxReactiveRepositoryFactoryBean.class.getName();
    }

    @Override
    protected Collection<Class<? extends Annotation>> getIdentifyingAnnotations() {
        return List.of(DocumentTable.class);
    }

    @Override
    protected Collection<Class<?>> getIdentifyingTypes() {
        return List.of(ReactiveDocumentRepository.class);
    }

    /** Only reactive repository interfaces; the others are {@link HalyxRepositoryConfigurationExtension}'s. */
    @Override
    protected boolean useRepositoryConfiguration(RepositoryMetadata metadata) {
        return metadata.isReactiveRepository();
    }
}
