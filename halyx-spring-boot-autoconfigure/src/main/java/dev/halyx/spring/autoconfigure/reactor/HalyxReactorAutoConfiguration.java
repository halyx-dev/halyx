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

import dev.halyx.Halyx;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * With {@code halyx-reactor} on the classpath: a {@link ReactiveHalyx} bean over the application's
 * {@link Halyx}, and a reactive view of every service bean from {@code halyx.*} properties, named
 * {@code <name>Reactive} and qualified {@code <name>}:
 *
 * <pre>{@code
 * HalyxController(@Qualifier("invoices") ReactiveBlobStore invoices, ReactiveHalyx reactiveHalyx) { ... }
 * }</pre>
 *
 * Calls run on {@code halyx.reactor.scheduler}: virtual threads by default.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class)
@ConditionalOnClass(ReactiveHalyx.class)
@ConditionalOnBean(Halyx.class)
@ConditionalOnProperty(prefix = "halyx.reactor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HalyxReactorProperties.class)
public class HalyxReactorAutoConfiguration {

    public static final String REACTIVE_HALYX_BEAN = "reactiveHalyx";

    /** Closing it disposes of the scheduler only when it made it (virtual threads). */
    @Bean(name = REACTIVE_HALYX_BEAN, destroyMethod = "close")
    @ConditionalOnMissingBean
    public ReactiveHalyx reactiveHalyx(Halyx halyx, HalyxReactorProperties properties, BeanFactory beans) {
        ReactiveHalyx.Builder builder = ReactiveHalyx.builder(halyx).maxConcurrency(properties.maxConcurrency());
        switch (properties.scheduler()) {
            case "virtual-threads" -> { }
            case "bounded-elastic" -> builder.scheduler(Schedulers.boundedElastic());
            default -> {
                if (!beans.containsBean(properties.scheduler())) {
                    throw new IllegalStateException("halyx.reactor.scheduler must be virtual-threads, bounded-elastic, "
                            + "or the name of a Scheduler bean; there is no bean named '" + properties.scheduler() + "'");
                }
                builder.scheduler(beans.getBean(properties.scheduler(), Scheduler.class));
            }
        }
        return builder.build();
    }

    @Bean
    static ReactiveServiceBeans halyxReactiveServiceBeans() {
        return new ReactiveServiceBeans();
    }
}
