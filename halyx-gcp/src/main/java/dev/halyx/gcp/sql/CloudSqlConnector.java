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
package dev.halyx.gcp.sql;

import dev.halyx.sql.SqlEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connections through the Cloud SQL Java connector (a JDBC socket factory), which finds the instance by
 * name, sets up TLS with ephemeral certificates and, for IAM, logs in with the identity's token itself.
 * No authorized networks or public IP are needed. The application adds the connector for its engine:
 * {@code com.google.cloud.sql:postgres-socket-factory} or
 * {@code com.google.cloud.sql:mysql-socket-factory-connector-j-8}.
 */
public final class CloudSqlConnector {

    private CloudSqlConnector() {
    }

    public static String socketFactory(SqlEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> "com.google.cloud.sql.postgres.SocketFactory";
            case MYSQL -> "com.google.cloud.sql.mysql.SocketFactory";
            case SQLSERVER -> "com.google.cloud.sql.sqlserver.SocketFactory";
        };
    }

    /** The host-less URL the socket factory connects. */
    public static String jdbcUrl(SqlEngine engine, String database) {
        return switch (engine) {
            case POSTGRESQL -> "jdbc:postgresql:///" + database;
            case MYSQL -> "jdbc:mysql:///" + database;
            case SQLSERVER -> "jdbc:sqlserver://localhost;databaseName=" + database;
        };
    }

    public static Map<String, String> properties(SqlEngine engine, String instance, boolean iam) {
        String factory = socketFactory(engine);
        try {
            Class.forName(factory, false, CloudSqlConnector.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("gcp.sql.instance is set but the Cloud SQL connector is not on the "
                    + "classpath: add " + switch (engine) {
                        case POSTGRESQL -> "com.google.cloud.sql:postgres-socket-factory";
                        case MYSQL -> "com.google.cloud.sql:mysql-socket-factory-connector-j-8";
                        case SQLSERVER -> "com.google.cloud.sql:cloud-sql-connector-jdbc-sqlserver";
                    }, e);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        if (engine == SqlEngine.SQLSERVER) {
            properties.put("socketFactoryClass", factory);
            properties.put("socketFactoryConstructorArg", instance);
        } else {
            properties.put("socketFactory", factory);
            properties.put("cloudSqlInstance", instance);
        }
        if (iam) {
            properties.put("enableIamAuth", "true");
            if (engine == SqlEngine.POSTGRESQL) {
                properties.put("sslmode", "disable"); // the connector's own tunnel is already TLS
            }
        }
        return properties;
    }
}
