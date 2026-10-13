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
package dev.halyx.aws.rds;

import dev.halyx.sql.spi.SqlCredentialsSource;
import dev.halyx.sql.spi.SqlLogin;
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
 * RDS IAM authentication tokens: a SigV4-presigned {@code GET https://<host>:<port>/?Action=connect&DBUser=<user>}
 * for the {@code rds-db} service, minus the scheme, used as the database password. Signed locally (no
 * request to AWS), the same as {@code RdsUtilities.generateAuthenticationToken}. Tokens are valid for new
 * connections for 15 minutes; established connections stay logged in. The identity needs
 * {@code rds-db:connect} on the database user.
 */
public final class RdsIamTokens implements SqlCredentialsSource {

    static final Duration VALIDITY = Duration.ofMinutes(15);

    private final String host;
    private final int port;
    private final String username;
    private final String region;
    private final AwsCredentialsProvider credentials;
    private final Clock clock;
    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

    public RdsIamTokens(String host, int port, String username, String region, AwsCredentialsProvider credentials) {
        this(host, port, username, region, credentials, Clock.systemUTC());
    }

    RdsIamTokens(String host, int port, String username, String region, AwsCredentialsProvider credentials,
                 Clock clock) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.username = Objects.requireNonNull(username, "username");
        this.region = Objects.requireNonNull(region, "region");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = clock;
    }

    @Override
    public SqlLogin login() {
        Instant now = clock.instant();
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("https")
                .host(host)
                .port(port)
                .encodedPath("/")
                .putRawQueryParameter("Action", "connect")
                .putRawQueryParameter("DBUser", username)
                .build();
        AwsCredentialsIdentity identity = credentials.resolveIdentity().join();
        var signed = signer.sign(r -> r.identity(identity)
                .request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "rds-db")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, VALIDITY)
                .putProperty(HttpSigner.SIGNING_CLOCK, clock));
        String token = signed.request().getUri().toString().replaceFirst("^https://", "");
        return SqlLogin.token(username, token, now.plus(VALIDITY));
    }
}
