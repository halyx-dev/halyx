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
package dev.halyx.aws.elasticache;

import dev.halyx.redis.RedisCredentialsSource;
import dev.halyx.redis.RedisLogin;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * ElastiCache IAM authentication tokens: a SigV4-presigned {@code GET http://<cache-name>/?Action=connect&User=<user-id>}
 * request for the {@code elasticache} service, minus the scheme. The user id is the AUTH username.
 * Tokens are valid for new connections for 15 minutes; established connections stay authenticated.
 */
public final class ElastiCacheIamTokens implements RedisCredentialsSource {

    static final Duration VALIDITY = Duration.ofMinutes(15);

    private final String cacheName;
    private final String userId;
    private final String region;
    private final boolean serverless;
    private final AwsCredentialsProvider credentials;
    private final Clock clock;
    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

    public ElastiCacheIamTokens(String cacheName, String userId, String region, boolean serverless,
                                AwsCredentialsProvider credentials) {
        this(cacheName, userId, region, serverless, credentials, Clock.systemUTC());
    }

    ElastiCacheIamTokens(String cacheName, String userId, String region, boolean serverless,
                         AwsCredentialsProvider credentials, Clock clock) {
        this.cacheName = Objects.requireNonNull(cacheName, "cacheName");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.region = Objects.requireNonNull(region, "region");
        this.serverless = serverless;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = clock;
    }

    @Override
    public RedisLogin login() {
        Instant now = clock.instant();
        SdkHttpFullRequest.Builder request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("http")
                .host(cacheName)
                .encodedPath("/")
                .putRawQueryParameter("Action", "connect")
                .putRawQueryParameter("User", userId);
        if (serverless) {
            request.putRawQueryParameter("ResourceType", "ServerlessCache");
        }
        AwsCredentialsIdentity identity = credentials.resolveIdentity().join();
        var signed = signer.sign(r -> r.identity(identity)
                .request(request.build())
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "elasticache")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, VALIDITY)
                .putProperty(HttpSigner.SIGNING_CLOCK, clock));
        String token = signed.request().getUri().toString().replaceFirst("^http://", "");
        return RedisLogin.token(userId, token, now.plus(VALIDITY));
    }
}
