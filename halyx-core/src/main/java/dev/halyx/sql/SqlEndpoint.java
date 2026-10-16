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
package dev.halyx.sql;

import dev.halyx.sql.spi.SqlCredentialsSource;

import java.util.Objects;

/**
 * Where and how a provider connects to a database, for clients other than Halyx' JDBC pool, such as R2DBC
 * ({@code halyx-r2dbc}): the host, port and database, the login each new connection uses, and whether TLS is
 * required. With {@code SqlAuth.iam}, {@code credentials} issues the cloud's tokens (RDS IAM, Entra ID, Cloud SQL
 * IAM), each valid for a short time; ask it for every new connection, or cache logins until shortly before they
 * expire, as {@code CachingCredentialsSource} does.
 *
 * @param engine      the database engine
 * @param host        the host to connect to
 * @param port        the port
 * @param database    the database name
 * @param credentials the login for a new connection
 * @param tlsRequired whether connections must use TLS: always with cloud IAM logins
 */
public record SqlEndpoint(SqlEngine engine, String host, int port, String database, SqlCredentialsSource credentials,
                          boolean tlsRequired) {

    public SqlEndpoint {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(credentials, "credentials");
    }
}
