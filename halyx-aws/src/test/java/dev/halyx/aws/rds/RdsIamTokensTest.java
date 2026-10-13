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

import org.junit.jupiter.api.Test;
import dev.halyx.sql.spi.SqlLogin;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RdsIamTokensTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void tokenIsAPresignedConnectUrlWithoutScheme() {
        var tokens = new RdsIamTokens("orders.cluster-abc.eu-west-1.rds.amazonaws.com", 5432, "orders_app",
                "eu-west-1", StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")),
                Clock.fixed(NOW, ZoneOffset.UTC));

        SqlLogin login = tokens.login();

        assertThat(login.username()).isEqualTo("orders_app");
        assertThat(login.expiresAt()).isEqualTo(NOW.plus(RdsIamTokens.VALIDITY));
        assertThat(login.password())
                .startsWith("orders.cluster-abc.eu-west-1.rds.amazonaws.com:5432/?")
                .contains("Action=connect")
                .contains("DBUser=orders_app")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Credential=AKIDEXAMPLE%2F20261010%2Feu-west-1%2Frds-db%2Faws4_request")
                .contains("X-Amz-Date=20261010T120000Z")
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-Signature=")
                .doesNotContain("https://");
    }
}
