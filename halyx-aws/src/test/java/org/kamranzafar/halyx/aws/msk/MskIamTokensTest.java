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
package org.kamranzafar.halyx.aws.msk;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.kafka.KafkaLogin;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MskIamTokensTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void tokenIsABase64UrlPresignedConnectUrl() {
        var tokens = new MskIamTokens("eu-west-2",
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")),
                Clock.fixed(NOW, ZoneOffset.UTC));

        KafkaLogin login = tokens.login();

        assertThat(login.token()).doesNotContain("=", "+", "/");
        String url = new String(Base64.getUrlDecoder().decode(login.token()), StandardCharsets.UTF_8);
        assertThat(url)
                .startsWith("https://kafka.eu-west-2.amazonaws.com/?")
                .contains("Action=kafka-cluster%3AConnect")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Credential=AKIDEXAMPLE%2F20261010%2Feu-west-2%2Fkafka-cluster%2Faws4_request")
                .contains("X-Amz-Date=20261010T120000Z")
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-SignedHeaders=host")
                .contains("X-Amz-Signature=")
                .endsWith("&User-Agent=" + MskIamTokens.USER_AGENT);
        assertThat(login.principal()).isEqualTo("AKIDEXAMPLE");
        assertThat(login.issuedAt()).isEqualTo(NOW);
        assertThat(login.expiresAt()).isEqualTo(NOW.plus(MskIamTokens.VALIDITY));
    }
}
