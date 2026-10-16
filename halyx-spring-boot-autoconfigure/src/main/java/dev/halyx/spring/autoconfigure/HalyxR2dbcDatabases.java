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
package dev.halyx.spring.autoconfigure;

import dev.halyx.Halyx;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.r2dbc.R2dbcDatabases;
import io.r2dbc.pool.ConnectionPool;

/**
 * Creates the pooled R2DBC {@code ConnectionFactory} beans of {@code halyx.r2dbc-databases}. Registered only when
 * {@code halyx-r2dbc} is on the classpath, so applications without it never load R2DBC classes.
 */
public class HalyxR2dbcDatabases {

    static final String BEAN = "halyxR2dbcDatabases";
    static final String GROUP = "halyx.r2dbc-databases";

    private final Halyx halyx;

    public HalyxR2dbcDatabases(Halyx halyx) {
        this.halyx = halyx;
    }

    /** A pool with a fixed password or cloud IAM login; disposed of with the context. */
    public ConnectionPool connectionFactory(String provider, String name, HalyxProperties.DatabaseProperties database) {
        return connectionFactory(provider, name, database, null);
    }

    /** A pool whose password is read from {@code secrets} for every new connection. */
    public ConnectionPool connectionFactoryWithSecret(String provider, String name,
                                                      HalyxProperties.DatabaseProperties database, ParameterStore secrets) {
        return connectionFactory(provider, name, database, secrets);
    }

    private ConnectionPool connectionFactory(String provider, String name, HalyxProperties.DatabaseProperties database,
                                             ParameterStore secrets) {
        var auth = HalyxBlobStores.resolveAuth(GROUP + "." + name + ".auth", database.auth(), secrets);
        return R2dbcDatabases.connectionFactory(halyx.provider(provider), database.toSqlConfig(GROUP, name, auth));
    }
}
