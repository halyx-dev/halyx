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
package dev.halyx.r2dbc;

import dev.halyx.CloudContext;
import dev.halyx.sql.PoolSettings;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.sql.spi.CachingCredentialsSource;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;

/**
 * Pooled R2DBC connections that log in the way a provider's {@code Database} does: with a password, looked up
 * for every new connection so a rotated secret takes effect, or with the cloud's IAM tokens (Amazon RDS IAM,
 * Microsoft Entra ID, Cloud SQL IAM), fetched off the event loop and reused until shortly before they expire.
 * Established connections stay logged in.
 *
 * <pre>{@code
 * ConnectionPool orders = R2dbcDatabases.connectionFactory(halyx.provider("aws"),
 *         SqlConfig.builder(SqlEngine.POSTGRESQL)
 *                 .host("orders.cluster-abc.eu-west-1.rds.amazonaws.com")
 *                 .database("orders")
 *                 .auth(SqlAuth.iam("orders_app"))
 *                 .build());
 * DatabaseClient client = DatabaseClient.create(orders);      // Spring's, for example
 * }</pre>
 *
 * PostgreSQL needs {@code org.postgresql:r2dbc-postgresql}, MySQL {@code io.asyncer:r2dbc-mysql} and SQL Server
 * {@code io.r2dbc:r2dbc-mssql}, which takes passwords only. The pool's size and timeouts come from the config's
 * {@link PoolSettings}. Close the pool when done with it; it doesn't close with the Halyx context.
 */
public final class R2dbcDatabases {

    private R2dbcDatabases() {
    }

    /** A pool for {@code config}, connecting and logging in as {@code context}'s provider does. */
    public static ConnectionPool connectionFactory(CloudContext context, SqlConfig config) {
        return connectionFactory(context.sqlEndpoint(config), config);
    }

    /** A pool for an endpoint, sized by {@code config}'s pool settings and named after it. */
    public static ConnectionPool connectionFactory(SqlEndpoint endpoint, SqlConfig config) {
        LoginConnectionFactory logins = new LoginConnectionFactory(endpoint.engine(),
                new CachingCredentialsSource(endpoint.credentials()), Drivers.forEndpoint(endpoint));
        PoolSettings pool = config.pool();
        return new ConnectionPool(ConnectionPoolConfiguration.builder(logins)
                .name(config.name())
                .initialSize(0)
                .minIdle(pool.minIdle())
                .maxSize(pool.maxSize())
                .maxAcquireTime(pool.connectionTimeout())
                .maxIdleTime(pool.idleTimeout())
                .maxLifeTime(pool.maxLifetime())
                .build());
    }
}
