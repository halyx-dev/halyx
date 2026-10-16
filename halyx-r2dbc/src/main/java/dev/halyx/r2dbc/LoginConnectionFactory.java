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

import dev.halyx.sql.SqlEngine;
import dev.halyx.sql.spi.SqlCredentialsSource;
import dev.halyx.sql.spi.SqlLogin;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Function;

/**
 * Opens each new connection with a current login: asks {@code credentials} for it on a scheduler for blocking
 * work, since a cloud token may need a network call, then connects through the driver's factory for that login.
 * Driver factories are reused while the login stays the same.
 */
final class LoginConnectionFactory implements ConnectionFactory {

    private final SqlEngine engine;
    private final SqlCredentialsSource credentials;
    private final Function<SqlLogin, ConnectionFactory> drivers;
    private volatile Driver current;

    private record Driver(SqlLogin login, ConnectionFactory factory) {
    }

    LoginConnectionFactory(SqlEngine engine, SqlCredentialsSource credentials,
                           Function<SqlLogin, ConnectionFactory> drivers) {
        this.engine = engine;
        this.credentials = credentials;
        this.drivers = drivers;
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.fromCallable(credentials::login)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(login -> Mono.from(driver(login).create()));
    }

    private ConnectionFactory driver(SqlLogin login) {
        Driver driver = current;
        if (driver == null || driver.login() != login) {
            driver = new Driver(login, drivers.apply(login));
            current = driver;
        }
        return driver.factory();
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        String name = switch (engine) {
            case POSTGRESQL -> "PostgreSQL";
            case MYSQL -> "MySQL";
            case SQLSERVER -> "Microsoft SQL Server";
        };
        return () -> name;
    }
}
