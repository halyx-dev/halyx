package org.kamranzafar.stratum.spring.autoconfigure;

import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end: an application configured purely through properties, against S3 on Floci. */
@Testcontainers(disabledWithoutDocker = true)
class StratumFlociS3IT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    @Test
    void configuresS3FromProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StratumAutoConfiguration.class))
                .withPropertyValues(
                        "stratum.providers.aws.type=aws",
                        "stratum.providers.aws.region=us-east-1",
                        "stratum.providers.aws.endpoint=http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566),
                        "stratum.providers.aws.credentials.type=static",
                        "stratum.providers.aws.credentials.id=test",
                        "stratum.providers.aws.credentials.secret=test",
                        "stratum.providers.aws.properties.aws.s3.path-style=true",
                        "stratum.blob-stores.invoices.bucket=spring-invoices")
                .run(context -> {
                    BlobStore invoices = context.getBean("invoices", BlobStore.class);

                    invoices.unwrap(S3Client.class).createBucket(b -> b.bucket("spring-invoices"));
                    invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofString("%PDF"));

                    assertThat(invoices.provider()).isEqualTo("aws");
                    assertThat(invoices.getBytes(BlobKey.of("2026/42.pdf"))).asString().isEqualTo("%PDF");
                });
    }
}
