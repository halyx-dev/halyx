package org.kamranzafar.cirrus.aws.rds;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;
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
