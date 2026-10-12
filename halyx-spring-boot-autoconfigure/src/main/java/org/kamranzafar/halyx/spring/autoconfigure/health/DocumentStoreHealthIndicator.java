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
package org.kamranzafar.halyx.spring.autoconfigure.health;

import org.kamranzafar.halyx.nosql.DocumentStore;
import org.kamranzafar.halyx.nosql.Key;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/** UP when the table answers an authenticated read (of a probe key that need not exist). */
public class DocumentStoreHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE = "halyx-health-probe";

    private final DocumentStore store;

    public DocumentStoreHealthIndicator(DocumentStore store) {
        super("Document store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider()).withDetail("table", store.table().name());
        store.get(store.table().hasSortKey() ? Key.of(PROBE, PROBE) : Key.of(PROBE));
        builder.up();
    }
}
