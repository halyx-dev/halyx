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

import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.Subscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Bean names taken twice fail with both properties named; Spring Boot settings Halyx replaces are reported. */
@ExtendWith(OutputCaptureExtension.class)
class HalyxBeanNamesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory");

    @Configuration(proxyBeanMethods = false)
    static class App {
        @Bean
        String invoices() {
            return "the application's";
        }
    }

    @Test
    void keyUsedInTwoGroupsFailsNamingBoth() {
        runner.withPropertyValues("halyx.topics.orders.name=orders", "halyx.subscriptions.orders.topic=orders")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "halyx.subscriptions.orders: the bean name 'orders' is already used by halyx.topics.orders. "
                                + "Rename the key, which is the bean name, and set halyx.subscriptions.orders.name to "
                                + "keep the cloud resource's current name"));
    }

    @Test
    void keyUsedByAnApplicationBeanFailsNamingIt() {
        runner.withUserConfiguration(App.class).withPropertyValues("halyx.blob-stores.invoices.bucket=acme-invoices")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "halyx.blob-stores.invoices: the bean name 'invoices' is already used by bean 'invoices'"));
    }

    @Test
    void renamedKeysKeepTheirResourceNames() {
        runner.withPropertyValues("halyx.topics.ordersPublisher.name=orders",
                        "halyx.subscriptions.ordersSubscription.name=orders",
                        "halyx.subscriptions.ordersSubscription.topic=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("ordersPublisher", Publisher.class)).isNotNull();
                    assertThat(context.getBean("ordersSubscription", Subscription.class)).isNotNull();
                });
    }

    @Test
    void springDatasourceNextToHalyxDatabasesIsReported(CapturedOutput output) {
        // Lazy, so the DataSource is never created: the warning comes from registering it.
        runner.withBean(LazyInitializationBeanFactoryPostProcessor.class)
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost/other",
                        "halyx.databases.orders.engine=postgresql", "halyx.databases.orders.host=localhost",
                        "halyx.databases.orders.database=orders", "halyx.databases.orders.auth.username=app",
                        "halyx.databases.orders.auth.password=p")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).contains("spring.datasource.* is set but ignored: halyx.databases [orders] "
                            + "defines the DataSource beans, so Spring Boot does not create its own");
                });
    }

    @Test
    void nothingIsReportedWithoutSpringDatasource(CapturedOutput output) {
        runner.withBean(LazyInitializationBeanFactoryPostProcessor.class)
                .withPropertyValues("halyx.databases.orders.engine=postgresql", "halyx.databases.orders.host=localhost",
                        "halyx.databases.orders.database=orders", "halyx.databases.orders.auth.username=app",
                        "halyx.databases.orders.auth.password=p")
                .run(context -> assertThat(output).doesNotContain("is set but ignored"));
    }
}
