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
package dev.halyx.samples.orders;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The {@code sql} feature: confirmed orders in a {@code ledger} table, through {@code JdbcTemplate} on the
 * {@code ledger} Halyx {@code DataSource}, which logs in with the cloud's IAM tokens. Boot creates the table from
 * {@code schema.sql}.
 */
@Component
@Profile("sql")
class LedgerService {

    record Entry(String orderId, String customerId, BigDecimal total, String currency, Instant recordedAt) {
    }

    private final JdbcTemplate jdbc;

    LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the order once; recording it again, after a redelivery, changes nothing. */
    void record(Order order) {
        try {
            jdbc.update("INSERT INTO ledger (order_id, customer_id, total, currency, recorded_at) VALUES (?, ?, ?, ?, ?)",
                    order.orderId(), order.customerId(), order.total(), order.currency(), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException alreadyRecorded) {
            // a redelivered message: the first delivery recorded it
        }
    }

    List<Entry> entries(String customerId) {
        return jdbc.query("SELECT order_id, customer_id, total, currency, recorded_at FROM ledger "
                        + "WHERE customer_id = ? ORDER BY order_id",
                (row, n) -> new Entry(row.getString(1), row.getString(2), row.getBigDecimal(3), row.getString(4),
                        row.getTimestamp(5).toInstant()),
                customerId);
    }
}
