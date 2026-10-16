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
package dev.halyx.spring.r2dbc;

import io.r2dbc.pool.ConnectionPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot and Spring Data R2DBC on a real cloud's PostgreSQL (tier 2), logging in with the cloud's identity
 * through {@code halyx.r2dbc-databases}, on Boot 3.5 and 4: Amazon RDS IAM, Microsoft Entra ID or Cloud SQL IAM.
 * Runs only with {@code -Dhalyx.real.spring.r2dbc=true}, from the real-cloud scripts' tier 2, which pass the
 * provider type ({@code halyx.real.spring.provider}), its properties ({@code halyx.real.spring.provider-properties},
 * as {@code key=value,...}), the host and the IAM user ({@code halyx.real.spring.r2dbc.host} and {@code .user}, the
 * user omitted where the provider derives it).
 *
 * <p>The IAM users can't create tables, so the repository works on a temporary table over a one-connection pool.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.r2dbc", matches = "true")
class RealSpringR2dbcIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private ApplicationContextRunner runner() {
        String user = System.getProperty("halyx.real.spring.r2dbc.user", "");
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.r2dbc-databases.orders.engine=postgresql",
                "halyx.r2dbc-databases.orders.host=" + System.getProperty("halyx.real.spring.r2dbc.host"),
                "halyx.r2dbc-databases.orders.database=app",
                "halyx.r2dbc-databases.orders.auth.type=iam",
                "halyx.r2dbc-databases.orders.pool.max-size=1",
                "halyx.r2dbc-databases.orders.pool.min-idle=0"));
        if (!user.isBlank()) {
            properties.add("halyx.r2dbc-databases.orders.auth.username=" + user);
        }
        for (String pair : System.getProperty("halyx.real.spring.provider-properties", "").split(",")) {
            if (!pair.isBlank()) {
                properties.add("halyx.providers.cloud.properties." + pair.trim());
            }
        }
        return new ApplicationContextRunner()
                .withConfiguration(HalyxR2dbcIT.configurations())
                .withUserConfiguration(HalyxR2dbcIT.Application.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    void springDataR2dbcLogsInWithTheCloudIdentity() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("orders")).isInstanceOf(ConnectionPool.class);
            DatabaseClient client = context.getBean(DatabaseClient.class);

            String user = client.sql("SELECT current_user").map(row -> row.get(0, String.class)).one().block(WAIT);
            String expected = System.getProperty("halyx.real.spring.r2dbc.user", "");
            if (!expected.isBlank()) {
                assertThat(user).isEqualTo(expected);
            }
            assertThat(client.sql("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                    .map(row -> row.get(0, Boolean.class)).one().block(WAIT)).as("TLS").isTrue();

            client.sql("CREATE TEMPORARY TABLE invoice (id BIGSERIAL PRIMARY KEY, customer TEXT, total NUMERIC)")
                    .then().block(WAIT);
            InvoiceRepository invoices = context.getBean(InvoiceRepository.class);
            invoices.save(new Invoice(null, "acme", new BigDecimal("12.50"))).block(WAIT);
            invoices.save(new Invoice(null, "globex", BigDecimal.ONE)).block(WAIT);
            assertThat(invoices.findByCustomerOrderByIdAsc("acme").map(Invoice::customer).collectList().block(WAIT))
                    .containsExactly("acme");
        });
    }
}
