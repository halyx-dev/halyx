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
        // The PostgreSQL and MySQL connectors are test dependencies (for the real Cloud SQL tests); SQL Server's isn't.
        assertThatThrownBy(() -> CloudSqlConnector.properties(SqlEngine.SQLSERVER, "p:r:i", true))
                .hasMessageContaining("cloud-sql-connector-jdbc-sqlserver");
    }

    @Test
    void connectorPropertiesNameTheFactoryAndInstance() {
        assertThat(CloudSqlConnector.properties(SqlEngine.POSTGRESQL, "p:r:i", true))
                .containsEntry("socketFactory", "com.google.cloud.sql.postgres.SocketFactory")
                .containsEntry("cloudSqlInstance", "p:r:i")
                .containsEntry("enableIamAuth", "true");
    }
}
