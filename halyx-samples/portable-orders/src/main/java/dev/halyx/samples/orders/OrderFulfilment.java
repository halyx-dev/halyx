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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Confirms orders announced on the {@code order-placed} topic, from the {@code fulfilment} subscription: writes the
 * receipt, records the order in the ledger when SQL is on, and marks it confirmed. Returning acknowledges the message
 * and throwing has it redelivered; each step can run twice, so a redelivery does no harm.
 */
@Component
class OrderFulfilment {

    private final OrderService orders;
    private final DocumentStore store;
    private final ReceiptService receipts;
    private final ObjectProvider<LedgerService> ledger;

    OrderFulfilment(OrderService orders, @Qualifier("orders") DocumentStore store, ReceiptService receipts,
                    ObjectProvider<LedgerService> ledger) {
        this.orders = orders;
        this.store = store;
        this.receipts = receipts;
        this.ledger = ledger;
    }

    @HalyxListener("fulfilment")
    void onOrderPlaced(String body) {
        String[] key = OrderMessages.key(body);
        // Some stores' reads are eventually consistent: throwing redelivers the message after a backoff.
        Order order = orders.find(key[0], key[1]).orElseThrow(() -> new IllegalStateException(
                "order " + key[1] + " of customer " + key[0] + " is not readable yet"));
        if (order.status() == OrderStatus.CONFIRMED) {
            return;
        }
        String receipt = receipts.write(order);
        ledger.ifAvailable(entries -> entries.record(order));
        store.update(Key.of(order.customerId(), order.orderId()), DocumentUpdate.builder()
                .set(Order.STATUS, OrderStatus.CONFIRMED.name())
                .set(Order.RECEIPT, receipt)
                .build());
    }
}
