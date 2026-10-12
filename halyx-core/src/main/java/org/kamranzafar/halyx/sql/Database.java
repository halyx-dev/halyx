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
package org.kamranzafar.halyx.sql;

import javax.sql.DataSource;

/**
 * A pooled database connection source. Hand {@link #dataSource()} to JDBC code, JPA, jOOQ or Spring.
 * Closing it closes the pool; the provider connection closes it too when the Halyx context closes.
 */
public interface Database extends AutoCloseable {

    SqlConfig config();

    /** The pooled data source; with IAM auth, each new physical connection logs in with a current token. */
    DataSource dataSource();

    /** True if a connection can be borrowed and is valid within {@code timeoutSeconds}. */
    boolean isHealthy(int timeoutSeconds);

    @Override
    void close();
}
