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
package org.kamranzafar.cirrus.sql;

/** Database engines Cirrus builds JDBC connections for. The JDBC driver itself is the application's dependency. */
public enum SqlEngine {
    /** PostgreSQL: RDS/Aurora PostgreSQL, Azure Database for PostgreSQL, Cloud SQL for PostgreSQL. Driver {@code org.postgresql:postgresql}. */
    POSTGRESQL(5432),
    /** MySQL: RDS/Aurora MySQL, Azure Database for MySQL, Cloud SQL for MySQL. Driver {@code com.mysql:mysql-connector-j}. */
    MYSQL(3306),
    /** SQL Server: RDS for SQL Server, Azure SQL Database. Driver {@code com.microsoft.sqlserver:mssql-jdbc}. */
    SQLSERVER(1433);

    private final int defaultPort;

    SqlEngine(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** The JDBC URL for a host, port and database, without credentials or extra properties. */
    public String jdbcUrl(String host, int port, String database) {
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database;
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database;
        };
    }

    /** The JDBC property that turns on encryption in transit, and the value that requires it. */
    public String[] tlsProperty() {
        return switch (this) {
            case POSTGRESQL -> new String[] {"sslmode", "require"};
            case MYSQL -> new String[] {"sslMode", "REQUIRED"};
            case SQLSERVER -> new String[] {"encrypt", "true"};
        };
    }
}
