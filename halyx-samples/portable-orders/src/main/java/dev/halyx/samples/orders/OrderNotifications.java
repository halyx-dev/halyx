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

import dev.halyx.events.CloudEvent;
import dev.halyx.events.EventPublisher;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.Key;
import dev.halyx.spring.messaging.HalyxListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code events} feature: each new order is a CloudEvent on the {@code order-events} bus (EventBridge, Event Grid
 * or Eventarc). The bus routes it to the {@code notifications} subscription, where the listener marks the order
 * notified, standing in for a customer notification.
 */
@Component
@Profile("events")
class OrderNotifications implements OrderPlacedHook {

    static final String PLACED = "dev.halyx.samples.order.placed";
    static final String SOURCE = "/portable-orders";

    private final EventPublisher events;
    private final DocumentStore store;

    OrderNotifications(@Qualifier("order-events") EventPublisher events, @Qualifier("orders") DocumentStore store) {
        this.events = events;
        this.store = store;
    }

    @Override
    public void placed(Order order) {
        events.publish(CloudEvent.builder(PLACED, SOURCE).subject(order.orderId())
                .json(OrderMessages.placed(order)).build());
    }

    /** Events from other sources may share the router's queue; they are acknowledged and ignored. */
    @HalyxListener("notifications")
    void onEvent(CloudEvent event) {
        if (!event.type().equals(PLACED) || !event.source().equals(SOURCE)) {
            return;
        }
        String[] key = OrderMessages.key(event.dataAsString().orElseThrow());
        store.update(Key.of(key[0], key[1]), DocumentUpdate.builder().set(Order.NOTIFIED, true).build());
    }
}
