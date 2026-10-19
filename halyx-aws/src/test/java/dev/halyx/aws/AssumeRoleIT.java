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
package dev.halyx.aws;

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Impersonation on AWS: an IAM role assumed through STS on Floci, starting from an access key. */
@Testcontainers(disabledWithoutDocker = true)
class AssumeRoleIT {

    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":"sts:AssumeRole"}]}""";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static URI endpoint;
    private static String roleArn;

    @BeforeAll
    static void createRole() {
        endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        try (IamClient iam = IamClient.builder().region(Region.AWS_GLOBAL).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            roleArn = iam.createRole(r -> r.roleName("orders").assumeRolePolicyDocument(TRUST_POLICY)).role().arn();
        }
    }

    private static Halyx halyx(Credentials credentials) {
        return Halyx.builder().register("aws", ProviderConfig.of("aws").region("us-east-1").endpoint(endpoint)
                .credentials(credentials).property(AwsProperties.S3_PATH_STYLE, "true")).build();
    }

    @Test
    void servicesUseTheRolesTemporaryCredentials() {
        var role = Credentials.impersonate(roleArn).from(Credentials.of("test", "test"))
                .sessionName("billing").lifetime(Duration.ofMinutes(15));
        try (Halyx halyx = halyx(role)) {
            BlobStore invoices = halyx.provider("aws").blobStore("assumed-role");
            S3Client s3 = invoices.unwrap(S3Client.class);
            s3.createBucket(b -> b.bucket("assumed-role"));

            invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofString("%PDF"));

            assertThat(invoices.getBytes(BlobKey.of("2026/42.pdf"))).asString().isEqualTo("%PDF");
            var credentials = s3.serviceClientConfiguration().credentialsProvider().resolveIdentity().join();
            assertThat(credentials).isInstanceOf(AwsSessionCredentialsIdentity.class);
            assertThat(credentials.accessKeyId()).isNotEqualTo("test");
        }
    }

    @Test
    void aRoleThatCannotBeAssumedFailsTheFirstCall() {
        var role = Credentials.impersonate("arn:aws:iam::000000000000:role/missing").from(Credentials.of("test", "test"));
        try (Halyx halyx = halyx(role)) {
            BlobStore store = halyx.provider("aws").blobStore("assumed-role");

            assertThatThrownBy(() -> store.exists(BlobKey.of("any"))).satisfies(error -> {
                Throwable cause = error;
                while (cause != null && !(cause instanceof StsException)) {
                    cause = cause.getCause();
                }
                assertThat(cause).as("an STS error in the chain of %s", error).isNotNull();
            });
        }
    }

    @Test
    void impersonationNeedsARoleArn() {
        try (Halyx halyx = halyx(Credentials.impersonate("orders@my-project.iam.gserviceaccount.com"))) {
            assertThatThrownBy(() -> halyx.provider("aws").blobStore("any")).hasMessageContaining("IAM role");
        }
    }

    @Test
    void servicePrincipalsAreForAzure() {
        try (Halyx halyx = halyx(Credentials.servicePrincipal("tenant", "client", "secret"))) {
            assertThatThrownBy(() -> halyx.provider("aws").blobStore("any")).hasMessageContaining("Azure, not AWS");
        }
    }
}
