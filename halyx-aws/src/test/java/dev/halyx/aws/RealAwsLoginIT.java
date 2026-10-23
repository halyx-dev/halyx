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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cloud logins on real AWS (tier 5): an IAM role assumed through STS from the default chain, with the external ID
 * its trust policy requires, and a bucket the role may use. Runs only with {@code -Dhalyx.real.aws.logins=true},
 * from {@code cloud-testing/scripts/aws-real-cloud.sh}, which passes the role and the bucket, and the external ID in
 * {@code HALYX_LOGIN_EXTERNAL_ID}.
 */
@EnabledIfSystemProperty(named = "halyx.real.aws.logins", matches = "true")
class RealAwsLoginIT {

    private static final String RUN = System.getProperty("halyx.real.aws.run", "local");
    private static final String ROLE = System.getProperty("halyx.real.aws.logins.role");
    private static final String EXTERNAL_ID = System.getenv("HALYX_LOGIN_EXTERNAL_ID");
    private static final String BUCKET = System.getProperty("halyx.real.aws.logins.bucket");
    private static final Region REGION = Region.of(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"));

    /** A new role can be refused for a few seconds, until IAM has propagated it. */
    @BeforeAll
    static void waitForTheRole() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(90);
        try (StsClient sts = StsClient.builder().region(REGION).build()) {
            while (true) {
                try {
                    sts.assumeRole(r -> r.roleArn(ROLE).externalId(EXTERNAL_ID).roleSessionName("halyx-wait"));
                    return;
                } catch (StsException e) {
                    if (Instant.now().isAfter(deadline)) {
                        throw e;
                    }
                    Thread.sleep(3000);
                }
            }
        }
    }

    private static Halyx halyx(Credentials credentials) {
        return Halyx.builder().register("aws", ProviderConfig.of("aws").region(REGION.id()).credentials(credentials))
                .build();
    }

    @Test
    void servicesActAsTheAssumedRole() {
        var role = Credentials.impersonate(ROLE).externalId(EXTERNAL_ID).sessionName("halyx-" + RUN)
                .lifetime(Duration.ofMinutes(15));
        try (Halyx halyx = halyx(role)) {
            BlobStore invoices = halyx.provider("aws").blobStore(BUCKET);
            BlobKey key = BlobKey.of("logins/" + RUN + ".txt");

            invoices.put(key, BlobContent.ofString("assumed"));

            assertThat(invoices.getBytes(key)).asString().isEqualTo("assumed");
            invoices.delete(key);
            var provider = invoices.unwrap(S3Client.class).serviceClientConfiguration().credentialsProvider();
            try (StsClient sts = StsClient.builder().region(REGION).credentialsProvider(provider).build()) {
                String arn = sts.getCallerIdentity().arn();
                String roleName = ROLE.substring(ROLE.lastIndexOf('/') + 1);
                assertThat(arn).endsWith(":assumed-role/" + roleName + "/halyx-" + RUN);
            }
        }
    }

    @Test
    void aWrongExternalIdIsRefused() {
        try (Halyx halyx = halyx(Credentials.impersonate(ROLE).externalId("not-" + EXTERNAL_ID))) {
            BlobStore invoices = halyx.provider("aws").blobStore(BUCKET);

            assertThatThrownBy(() -> invoices.exists(BlobKey.of("any"))).satisfies(error -> {
                Throwable cause = error;
                while (cause != null && !(cause instanceof StsException)) {
                    cause = cause.getCause();
                }
                assertThat(cause).as("an STS error in the chain of %s", error).isNotNull();
                assertThat(((StsException) cause).statusCode()).isEqualTo(403);
            });
        }
    }

    @Test
    void theDefaultChainActsAsTheTestIdentity() {
        // The contrast for the first test: without impersonation, the same calls are the test identity's own.
        try (Halyx halyx = halyx(Credentials.defaultChain())) {
            var provider = halyx.provider("aws").blobStore(BUCKET).unwrap(S3Client.class)
                    .serviceClientConfiguration().credentialsProvider();
            try (StsClient sts = StsClient.builder().region(REGION).credentialsProvider(provider).build()) {
                assertThat(sts.getCallerIdentity().arn()).doesNotContain(":assumed-role/");
            }
        }
    }
}
