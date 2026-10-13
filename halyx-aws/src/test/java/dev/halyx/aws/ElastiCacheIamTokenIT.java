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

import org.junit.jupiter.api.Test;
import dev.halyx.aws.elasticache.ElastiCacheIamTokens;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ElastiCache IAM tokens checked by Floci's SigV4 token validator ({@code ValidateIamAuthToken}),
 * the same validator its RESP proxy uses for IAM logins.
 */
@Testcontainers(disabledWithoutDocker = true)
class ElastiCacheIamTokenIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static ElastiCacheIamTokens tokens(String secret) {
        return new ElastiCacheIamTokens("orders-cache", "orders-app", "us-east-1", false,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", secret)));
    }

    @Test
    void generatedTokenIsAcceptedWithItsCacheAndUser() throws Exception {
        HttpResponse<String> response = validate(tokens("test").login().password());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("<Valid>true</Valid>")
                .contains("<ClusterId>orders-cache</ClusterId>")
                .contains("<UserId>orders-app</UserId>");
    }

    @Test
    void tokenSignedWithWrongSecretIsRejected() throws Exception {
        HttpResponse<String> response = validate(tokens("not-the-secret").login().password());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("SignatureDoesNotMatch");
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = tokens("test").login().password().replace("User=orders-app", "User=admin");

        assertThat(validate(token).statusCode()).isEqualTo(403);
    }

    /** POST Action=ValidateIamAuthToken, SigV4-signed for the elasticache service as an SDK would. */
    private static HttpResponse<String> validate(String token) throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566) + "/");
        String body = "Action=ValidateIamAuthToken&Version=2015-02-02&Token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(endpoint)
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .contentStreamProvider(() -> new ByteArrayInputStream(bytes))
                .build();
        var signed = AwsV4HttpSigner.create().sign(r -> r
                .identity(AwsBasicCredentials.create("test", "test"))
                .request(request)
                .payload(request.contentStreamProvider().orElseThrow())
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "elasticache")
                .putProperty(AwsV4HttpSigner.REGION_NAME, "us-east-1"));
        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        signed.request().headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                values.forEach(value -> http.header(name, value));
            }
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(http.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
