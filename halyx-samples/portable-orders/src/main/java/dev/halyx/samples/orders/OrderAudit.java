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

import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.Key;
import dev.halyx.spring.messaging.HalyxListener;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code streams} feature: each new order is a record on the {@code order-audit} stream (MSK, Event Hubs or
 * Managed Kafka), keyed by order id so an order's records stay in order. The {@code auditor} consumer group marks the
 * order audited.
 */
@Component
@Profile("streams")
class OrderAudit implements OrderPlacedHook {

    private final StreamProducer audit;
    private final DocumentStore store;

    OrderAudit(@Qualifier("order-audit") StreamProducer audit, @Qualifier("orders") DocumentStore store) {
        this.audit = audit;
        this.store = store;
    }

    @Override
    public void placed(Order order) {
        audit.send(order.orderId(), OrderMessages.placed(order));
    }

    @HalyxListener("auditor")
    void onRecord(String value) {
        String[] key = OrderMessages.key(value);
        store.update(Key.of(key[0], key[1]), DocumentUpdate.builder().set(Order.AUDITED, true).build());
    }
}
