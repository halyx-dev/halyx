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

import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Halyx' reactive Spring Data repositories ({@code ReactiveDocumentRepository}) in the application's packages, over
 * the {@code DocumentStore} beans of {@code halyx.document-stores}, running on the {@code ReactiveHalyx} bean.
 * Needs {@code halyx-reactor}; backs off when the application uses {@code @EnableHalyxReactiveRepositories} itself,
 * and when {@code halyx.document-repositories.enabled=false}.
 */
@AutoConfiguration(after = {HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class})
@ConditionalOnClass(name = {"dev.halyx.spring.data.document.ReactiveDocumentRepository", "dev.halyx.reactor.ReactiveHalyx"})
@ConditionalOnMissingBean(type = "dev.halyx.spring.data.document.repository.HalyxReactiveRepositoryFactoryBean")
@ConditionalOnProperty(prefix = "halyx.document-repositories", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(HalyxReactiveRepositoriesRegistrar.class)
public class HalyxReactiveRepositoriesAutoConfiguration {
}
