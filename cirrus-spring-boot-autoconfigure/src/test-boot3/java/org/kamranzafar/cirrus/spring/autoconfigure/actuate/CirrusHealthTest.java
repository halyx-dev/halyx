package org.kamranzafar.cirrus.spring.autoconfigure.actuate;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusAutoConfiguration;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusBuilderCustomizer;
import org.kamranzafar.cirrus.spring.autoconfigure.TestProviders;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Health contributor on Spring Boot 3 (org.springframework.boot.actuate.health). */
class CirrusHealthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CirrusAutoConfiguration.class, CirrusActuatorHealthContributorAutoConfiguration.class));

    @Test
    void oneIndicatorPerBlobStore() {
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                        "cirrus.blob-stores.a.bucket=a", "cirrus.blob-stores.b.bucket=b")
                .run(context -> {
                    var composite = context.getBean("cirrusHealthContributor", CompositeHealthContributor.class);
                    var a = ((HealthIndicator) composite.getContributor("a")).health();
                    assertThat(a.getStatus()).isEqualTo(Status.UP);
                    assertThat(a.getDetails()).containsEntry("provider", "memory").containsEntry("bucket", "a");
                    assertThat(composite.getContributor("b")).isNotNull();
                });
    }

    @Test
    void downWhenTheStoreFails() {
        runner.withPropertyValues("cirrus.providers.flaky.type=flaky", "cirrus.blob-stores.store.bucket=b")
                .withBean(CirrusBuilderCustomizer.class,
                        () -> builder -> builder.plugin(new TestProviders.Flaky(Integer.MAX_VALUE)))
                .run(context -> {
                    var composite = context.getBean("cirrusHealthContributor", CompositeHealthContributor.class);
                    assertThat(((HealthIndicator) composite.getContributor("store")).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    static class Listener {
        @org.kamranzafar.cirrus.spring.messaging.CirrusListener("events")
        void on(String body) {
        }
    }

    @Test
    void messageConsumersReportTheirState() {
        runner.withPropertyValues("cirrus.providers.local.type=memory", "cirrus.subscriptions.events.topic=events")
                .withBean(Listener.class, Listener::new)
                .run(context -> {
                    var composite = context.getBean("cirrusHealthContributor", CompositeHealthContributor.class);
                    var messaging = ((HealthIndicator) composite.getContributor("messaging")).health();
                    assertThat(messaging.getStatus()).isEqualTo(Status.UP);
                    assertThat(messaging.getDetails().keySet()).singleElement().asString().startsWith("events#");
                });
    }

    @Test
    void cachesHaveIndicatorsToo() {
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                        "cirrus.blob-stores.files.bucket=files",
                        "cirrus.caches.sessions.endpoint=redis://localhost:6379")
                .run(context -> {
                    var composite = context.getBean("cirrusHealthContributor", CompositeHealthContributor.class);
                    var sessions = ((HealthIndicator) composite.getContributor("sessions")).health();
                    assertThat(sessions.getStatus()).isEqualTo(Status.UP);
                    assertThat(sessions.getDetails()).containsEntry("namespace", "sessions");
                    assertThat(composite.getContributor("files")).isNotNull();
                });
    }

    @Test
    void parameterStoresHaveIndicators() {
        runner.withPropertyValues("cirrus.providers.local.type=memory", "cirrus.parameter-stores.secrets.prefix=app")
                .run(context -> {
                    var composite = context.getBean("cirrusHealthContributor", CompositeHealthContributor.class);
                    var secrets = ((HealthIndicator) composite.getContributor("secrets")).health();
                    assertThat(secrets.getStatus()).isEqualTo(Status.UP);
                    assertThat(secrets.getDetails()).containsEntry("kind", "secrets");
                });
    }

    @Test
    void cachesAloneEnableHealth() {
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                        "cirrus.caches.sessions.endpoint=redis://localhost:6379")
                .run(context -> assertThat(context).hasBean("cirrusHealthContributor"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("cirrus.providers.local.type=memory", "cirrus.blob-stores.a.bucket=a",
                        "management.health.cirrus.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("cirrusHealthContributor"));
    }

    @Test
    void absentWithoutBlobStores() {
        runner.withPropertyValues("cirrus.providers.local.type=memory")
                .run(context -> assertThat(context).doesNotHaveBean("cirrusHealthContributor"));
    }
}
