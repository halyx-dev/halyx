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
package dev.halyx.aws.msk;

import dev.halyx.kafka.KafkaCredentialsSource;
import dev.halyx.kafka.KafkaLogin;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Amazon MSK IAM access control over SASL {@code OAUTHBEARER}: the token is a SigV4-presigned
 * {@code GET https://kafka.<region>.amazonaws.com/?Action=kafka-cluster:Connect} for the {@code kafka-cluster}
 * service, with a {@code User-Agent} parameter added, base64url-encoded without padding, the same as AWS's
 * MSK IAM SASL signer libraries. Signed locally (no request to AWS) and valid for 15 minutes; the identity needs
 * {@code kafka-cluster:Connect} on the cluster, plus the topic and group actions it uses.
 */
public final class MskIamTokens implements KafkaCredentialsSource {

    static final Duration VALIDITY = Duration.ofMinutes(15);
    static final String USER_AGENT = "halyx-msk-iam";

    private final String region;
    private final AwsCredentialsProvider credentials;
    private final Clock clock;
    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

    public MskIamTokens(String region, AwsCredentialsProvider credentials) {
        this(region, credentials, Clock.systemUTC());
    }

    MskIamTokens(String region, AwsCredentialsProvider credentials, Clock clock) {
        this.region = Objects.requireNonNull(region, "region");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = clock;
    }

    @Override
    public KafkaLogin login() {
        Instant now = clock.instant();
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("https")
                .host("kafka." + region + ".amazonaws.com")
                .encodedPath("/")
                .putRawQueryParameter("Action", "kafka-cluster:Connect")
                .build();
        AwsCredentialsIdentity identity = credentials.resolveIdentity().join();
        var signed = signer.sign(r -> r.identity(identity)
                .request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "kafka-cluster")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, VALIDITY)
                .putProperty(HttpSigner.SIGNING_CLOCK, clock));
        // The User-Agent parameter is added after signing; the service leaves it out of the signature.
        String url = signed.request().getUri() + "&User-Agent=" + USER_AGENT;
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
        return new KafkaLogin(token, identity.accessKeyId(), now, now.plus(VALIDITY));
    }
}
