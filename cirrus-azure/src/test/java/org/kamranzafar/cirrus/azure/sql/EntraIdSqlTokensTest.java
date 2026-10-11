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
package org.kamranzafar.cirrus.azure.sql;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.sql.SqlEngine;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntraIdSqlTokensTest {

    private static final OffsetDateTime EXPIRY = OffsetDateTime.of(2026, 10, 10, 13, 0, 0, 0, ZoneOffset.UTC);

    private final List<String> scopes = new ArrayList<>();
    private final TokenCredential credential = request -> {
        scopes.addAll(request.getScopes());
        return Mono.just(new AccessToken("entra-token", EXPIRY));
    };

    @Test
    void postgresAndMysqlUseTheTokenAsPassword() {
        SqlLogin login = new EntraIdSqlTokens(credential, SqlEngine.POSTGRESQL, "orders-app-identity").login();

        assertThat(scopes).containsExactly(EntraIdSqlTokens.OSS_RDBMS_SCOPE);
        assertThat(login.username()).isEqualTo("orders-app-identity");
        assertThat(login.password()).isEqualTo("entra-token");
        assertThat(login.expiresAt()).isEqualTo(EXPIRY.toInstant());
    }

    @Test
    void azureSqlUsesTheAccessTokenProperty() {
        SqlLogin login = new EntraIdSqlTokens(credential, SqlEngine.SQLSERVER, null).login();

        assertThat(scopes).containsExactly(EntraIdSqlTokens.AZURE_SQL_SCOPE);
        assertThat(login.username()).isNull();
        assertThat(login.password()).isNull();
        assertThat(login.connectionProperties()).containsEntry("accessToken", "entra-token");
    }

    @Test
    void postgresNeedsTheRoleName() {
        assertThatThrownBy(() -> new EntraIdSqlTokens(credential, SqlEngine.MYSQL, null))
                .hasMessageContaining("database role");
    }
}
