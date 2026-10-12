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
package org.kamranzafar.halyx.azure.sql;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.kamranzafar.halyx.sql.SqlEngine;
import org.kamranzafar.halyx.sql.spi.SqlCredentialsSource;
import org.kamranzafar.halyx.sql.spi.SqlLogin;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Microsoft Entra ID access tokens for database logins. Azure Database for PostgreSQL and MySQL take the
 * token as the password of the Entra user or managed identity's database role; Azure SQL takes it as the
 * driver's {@code accessToken} property, with no username or password.
 */
public final class EntraIdSqlTokens implements SqlCredentialsSource {

    /** Scope for Azure Database for PostgreSQL and MySQL flexible servers. */
    static final String OSS_RDBMS_SCOPE = "https://ossrdbms-aad.database.windows.net/.default";
    /** Scope for Azure SQL Database and Managed Instance. */
    static final String AZURE_SQL_SCOPE = "https://database.windows.net/.default";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final TokenCredential credential;
    private final SqlEngine engine;
    private final String username;

    public EntraIdSqlTokens(TokenCredential credential, SqlEngine engine, String username) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.username = username;
        if (engine != SqlEngine.SQLSERVER && username == null) {
            throw new IllegalArgumentException("Entra ID login to Azure Database for " + engine
                    + " needs the database role of the identity: SqlAuth.iam(\"<role>\")");
        }
    }

    @Override
    public SqlLogin login() {
        String scope = engine == SqlEngine.SQLSERVER ? AZURE_SQL_SCOPE : OSS_RDBMS_SCOPE;
        AccessToken token = credential.getToken(new TokenRequestContext().addScopes(scope)).block(TIMEOUT);
        if (token == null) {
            throw new IllegalStateException("Entra ID returned no token for " + scope);
        }
        if (engine == SqlEngine.SQLSERVER) {
            return new SqlLogin(null, null, Map.of("accessToken", token.getToken()), token.getExpiresAt().toInstant());
        }
        return SqlLogin.token(username, token.getToken(), token.getExpiresAt().toInstant());
    }
}
