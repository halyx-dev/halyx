package org.kamranzafar.cirrus.gcp.sql;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.UserCredentials;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.sql.SqlEngine;

import java.security.KeyPairGenerator;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudSqlIamTokensTest {

    @Test
    void usernamesAreDerivedFromTheServiceAccount() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var account = ServiceAccountCredentials.newBuilder()
                .setClientEmail("orders-app@shop-prod.iam.gserviceaccount.com")
                .setPrivateKey(generator.generateKeyPair().getPrivate())
                .build();

        assertThat(new CloudSqlIamTokens(account, SqlEngine.POSTGRESQL, Optional.empty()).username())
                .isEqualTo("orders-app@shop-prod.iam");
        assertThat(new CloudSqlIamTokens(account, SqlEngine.MYSQL, Optional.empty()).username())
                .isEqualTo("orders-app");
        assertThat(new CloudSqlIamTokens(account, SqlEngine.MYSQL, Optional.of("explicit")).username())
                .isEqualTo("explicit");
    }

    @Test
    void userCredentialsNeedAnExplicitUsername() {
        var user = UserCredentials.newBuilder().setClientId("id").setClientSecret("secret").setRefreshToken("r").build();

        assertThatThrownBy(() -> new CloudSqlIamTokens(user, SqlEngine.POSTGRESQL, Optional.empty()))
                .hasMessageContaining("SqlAuth.iam");
    }

    @Test
    void connectorNeedsItsSocketFactoryOnTheClasspath() {
        assertThatThrownBy(() -> CloudSqlConnector.properties(SqlEngine.POSTGRESQL, "p:r:i", true))
                .hasMessageContaining("postgres-socket-factory");
    }
}
