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
package dev.halyx.spring.autoconfigure.health;

import org.junit.jupiter.api.Test;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.HalyxBuilderCustomizer;
import dev.halyx.spring.autoconfigure.TestProviders;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Health contributor on Spring Boot 4 (org.springframework.boot.health.contributor). */
class HalyxHealthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxHealthContributorAutoConfiguration.class));

    @Test
    void oneIndicatorPerBlobStore() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.blob-stores.a.bucket=a", "halyx.blob-stores.b.bucket=b")
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var a = ((HealthIndicator) composite.getContributor("a")).health();
                    assertThat(a.getStatus()).isEqualTo(Status.UP);
                    assertThat(a.getDetails()).containsEntry("provider", "memory").containsEntry("bucket", "a");
                    assertThat(composite.getContributor("b")).isNotNull();
                });
    }

    @Test
    void downWhenTheStoreFails() {
        runner.withPropertyValues("halyx.providers.flaky.type=flaky", "halyx.blob-stores.store.bucket=b")
                .withBean(HalyxBuilderCustomizer.class,
                        () -> builder -> builder.plugin(new TestProviders.Flaky(Integer.MAX_VALUE)))
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    assertThat(((HealthIndicator) composite.getContributor("store")).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    static class Listener {
        @dev.halyx.spring.messaging.HalyxListener("events")
        void on(String body) {
        }
    }

    @Test
    void messageConsumersReportTheirState() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.subscriptions.events.topic=events")
                .withBean(Listener.class, Listener::new)
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var messaging = ((HealthIndicator) composite.getContributor("messaging")).health();
                    assertThat(messaging.getStatus()).isEqualTo(Status.UP);
                    assertThat(messaging.getDetails().keySet()).singleElement().asString().startsWith("events#");
                });
    }

    static class RecordListener {
        @dev.halyx.spring.messaging.HalyxListener("payments")
        void on(String value) {
        }
    }

    @Test
    void streamProducersAndListenersHaveIndicators() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
                        "halyx.stream-producers.payments-out.topic=payments",
                        "halyx.stream-consumers.payments.start-position=earliest")
                .withBean(RecordListener.class, RecordListener::new)
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var producer = ((HealthIndicator) composite.getContributor("payments-out")).health();
                    assertThat(producer.getStatus()).isEqualTo(Status.UP);
                    assertThat(producer.getDetails()).containsEntry("topic", "payments").containsEntry("partitions", 3);
                    var streams = ((HealthIndicator) composite.getContributor("streams")).health();
                    assertThat(streams.getStatus()).isEqualTo(Status.UP);
                    assertThat(streams.getDetails().keySet()).singleElement().asString().startsWith("payments#");
                });
    }

    @Test
    void documentStoresHaveIndicators() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.document-stores.orders.sort-key=sk")
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var orders = ((HealthIndicator) composite.getContributor("orders")).health();
                    assertThat(orders.getStatus()).isEqualTo(Status.UP);
                    assertThat(orders.getDetails()).containsEntry("table", "orders");
                });
    }

    @Test
    void cachesHaveIndicatorsToo() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.blob-stores.files.bucket=files",
                        "halyx.caches.sessions.endpoint=redis://localhost:6379")
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var sessions = ((HealthIndicator) composite.getContributor("sessions")).health();
                    assertThat(sessions.getStatus()).isEqualTo(Status.UP);
                    assertThat(sessions.getDetails()).containsEntry("namespace", "sessions");
                    assertThat(composite.getContributor("files")).isNotNull();
                });
    }

    @Test
    void parameterStoresHaveIndicators() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.parameter-stores.secrets.prefix=app")
                .run(context -> {
                    var composite = context.getBean("halyxHealthContributor", CompositeHealthContributor.class);
                    var secrets = ((HealthIndicator) composite.getContributor("secrets")).health();
                    assertThat(secrets.getStatus()).isEqualTo(Status.UP);
                    assertThat(secrets.getDetails()).containsEntry("kind", "secrets");
                });
    }

    @Test
    void cachesAloneEnableHealth() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.caches.sessions.endpoint=redis://localhost:6379")
                .run(context -> assertThat(context).hasBean("halyxHealthContributor"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.blob-stores.a.bucket=a",
                        "management.health.halyx.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("halyxHealthContributor"));
    }

    @Test
    void absentWithoutBlobStores() {
        runner.withPropertyValues("halyx.providers.local.type=memory")
                .run(context -> assertThat(context).doesNotHaveBean("halyxHealthContributor"));
    }
}
