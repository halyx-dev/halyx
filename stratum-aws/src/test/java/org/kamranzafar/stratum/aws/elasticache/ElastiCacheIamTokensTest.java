package org.kamranzafar.stratum.aws.elasticache;

import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.redis.RedisLogin;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ElastiCacheIamTokensTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    private static final StaticCredentialsProvider KEYS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret"));

    @Test
    void tokenIsAPresignedConnectRequestWithoutScheme() {
        RedisLogin login = new ElastiCacheIamTokens("orders-cache", "orders-app", "eu-west-2", false, KEYS, CLOCK).login();
        String token = login.password();

        assertThat(login.username()).hasValue("orders-app");
        assertThat(token).startsWith("orders-cache/?").doesNotContain("http://");
        assertThat(token)
                .contains("Action=connect")
                .contains("User=orders-app")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Credential=AKIDEXAMPLE%2F20261010%2Feu-west-2%2Felasticache%2Faws4_request")
                .contains("X-Amz-Date=20261010T120000Z")
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-Signature=")
                .doesNotContain("ResourceType");
        assertThat(login.expiresAt()).hasValue(Instant.parse("2026-10-10T12:15:00Z"));
    }

    @Test
    void serverlessTokensNameTheResourceType() {
        String token = new ElastiCacheIamTokens("sessions", "app", "us-east-1", true, KEYS, CLOCK).login().password();

        assertThat(token).contains("ResourceType=ServerlessCache");
    }

    @Test
    void tokensAreDeterministicForTheSameInstant() {
        var tokens = new ElastiCacheIamTokens("c", "u", "us-east-1", false, KEYS, CLOCK);

        assertThat(tokens.login().password()).isEqualTo(tokens.login().password());
    }
}
