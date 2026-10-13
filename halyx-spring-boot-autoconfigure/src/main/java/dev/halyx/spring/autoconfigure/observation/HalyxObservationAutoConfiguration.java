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
package dev.halyx.spring.autoconfigure.observation;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Observes Halyx operations when the application has a Micrometer {@link ObservationRegistry}. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration" // Boot 4
})
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "halyx.observations", name = "enabled", matchIfMissing = true)
public class HalyxObservationAutoConfiguration {

    /** Outermost interceptor, so the observation covers retries and every other interceptor. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    public ObservationInterceptor halyxObservationInterceptor(ObservationRegistry registry) {
        return new ObservationInterceptor(registry);
    }
}
