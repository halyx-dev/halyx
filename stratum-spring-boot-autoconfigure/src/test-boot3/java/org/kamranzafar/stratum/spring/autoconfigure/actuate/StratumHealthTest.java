package org.kamranzafar.stratum.spring.autoconfigure.actuate;

import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.spring.autoconfigure.StratumBuilderCustomizer;
import org.kamranzafar.stratum.spring.autoconfigure.TestProviders;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Health contributor on Spring Boot 3 (org.springframework.boot.actuate.health). */
class StratumHealthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StratumAutoConfiguration.class, StratumActuatorHealthContributorAutoConfiguration.class));

    @Test
    void oneIndicatorPerBlobStore() {
        runner.withPropertyValues("stratum.providers.local.type=memory",
                        "stratum.blob-stores.a.bucket=a", "stratum.blob-stores.b.bucket=b")
                .run(context -> {
                    var composite = context.getBean("stratumHealthContributor", CompositeHealthContributor.class);
                    var a = ((HealthIndicator) composite.getContributor("a")).health();
                    assertThat(a.getStatus()).isEqualTo(Status.UP);
                    assertThat(a.getDetails()).containsEntry("provider", "memory").containsEntry("bucket", "a");
                    assertThat(composite.getContributor("b")).isNotNull();
                });
    }

    @Test
    void downWhenTheStoreFails() {
        runner.withPropertyValues("stratum.providers.flaky.type=flaky", "stratum.blob-stores.store.bucket=b")
                .withBean(StratumBuilderCustomizer.class,
                        () -> builder -> builder.plugin(new TestProviders.Flaky(Integer.MAX_VALUE)))
                .run(context -> {
                    var composite = context.getBean("stratumHealthContributor", CompositeHealthContributor.class);
                    assertThat(((HealthIndicator) composite.getContributor("store")).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Test
    void cachesHaveIndicatorsToo() {
        runner.withPropertyValues("stratum.providers.local.type=memory",
                        "stratum.blob-stores.files.bucket=files",
                        "stratum.caches.sessions.endpoint=redis://localhost:6379")
                .run(context -> {
                    var composite = context.getBean("stratumHealthContributor", CompositeHealthContributor.class);
                    var sessions = ((HealthIndicator) composite.getContributor("sessions")).health();
                    assertThat(sessions.getStatus()).isEqualTo(Status.UP);
                    assertThat(sessions.getDetails()).containsEntry("namespace", "sessions");
                    assertThat(composite.getContributor("files")).isNotNull();
                });
    }

    @Test
    void cachesAloneEnableHealth() {
        runner.withPropertyValues("stratum.providers.local.type=memory",
                        "stratum.caches.sessions.endpoint=redis://localhost:6379")
                .run(context -> assertThat(context).hasBean("stratumHealthContributor"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("stratum.providers.local.type=memory", "stratum.blob-stores.a.bucket=a",
                        "management.health.stratum.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("stratumHealthContributor"));
    }

    @Test
    void absentWithoutBlobStores() {
        runner.withPropertyValues("stratum.providers.local.type=memory")
                .run(context -> assertThat(context).doesNotHaveBean("stratumHealthContributor"));
    }
}
