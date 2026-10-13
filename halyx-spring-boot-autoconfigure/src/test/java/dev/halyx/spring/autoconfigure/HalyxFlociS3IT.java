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

import org.junit.jupiter.api.Test;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
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
class HalyxFlociS3IT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    @Test
    void configuresS3FromProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
                .withPropertyValues(
                        "halyx.providers.aws.type=aws",
                        "halyx.providers.aws.region=us-east-1",
                        "halyx.providers.aws.endpoint=http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566),
                        "halyx.providers.aws.credentials.type=static",
                        "halyx.providers.aws.credentials.id=test",
                        "halyx.providers.aws.credentials.secret=test",
                        "halyx.providers.aws.properties.aws.s3.path-style=true",
                        "halyx.blob-stores.invoices.bucket=spring-invoices")
                .run(context -> {
                    BlobStore invoices = context.getBean("invoices", BlobStore.class);

                    invoices.unwrap(S3Client.class).createBucket(b -> b.bucket("spring-invoices"));
                    invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofString("%PDF"));

                    assertThat(invoices.provider()).isEqualTo("aws");
                    assertThat(invoices.getBytes(BlobKey.of("2026/42.pdf"))).asString().isEqualTo("%PDF");
                });
    }
}
