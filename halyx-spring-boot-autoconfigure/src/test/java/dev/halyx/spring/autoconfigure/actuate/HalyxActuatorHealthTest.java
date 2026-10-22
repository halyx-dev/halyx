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
package dev.halyx.spring.autoconfigure.actuate;

import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.cache.HalyxCacheManagerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Health contributor on Spring Boot 3 (org.springframework.boot.actuate.health): the cache manager's entry. */
class HalyxActuatorHealthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class,
                    HalyxCacheManagerAutoConfiguration.class, HalyxActuatorHealthContributorAutoConfiguration.class));

    @Test
    void theCacheManagerHasAnIndicatorEvenAsTheOnlyHalyxBean() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.cache-manager.endpoint=redis://localhost:6379")
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var health = ((HealthIndicator) composite.getContributor("cache-manager")).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("provider", "memory");
                });
    }

    @Test
    void theCacheManagerIsDownWhenItsServerIsUnreachableEvenFailOpen() {
        runner.withPropertyValues("halyx.providers.aws.type=aws", "halyx.providers.aws.region=us-east-1",
                        "halyx.cache-manager.endpoint=redis://127.0.0.1:1", "halyx.cache-manager.auth.type=none",
                        "halyx.cache-manager.fail-open=true")
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    assertThat(((HealthIndicator) composite.getContributor("cache-manager")).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Test
    void aHalyxBeanNamedCacheManagerIsRefused() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.cache-manager.endpoint=redis://localhost:6379",
                        "halyx.blob-stores.cache-manager.bucket=b")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("The Halyx bean 'cache-manager' clashes with the halyx health entry of "
                                + "the same name, which reports halyx.cache-manager: rename the bean"));
    }
}
