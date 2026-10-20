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

import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.Publisher;
import dev.halyx.nosql.Key;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Places orders: stores each in the {@code orders} document store through a Spring Data repository, then announces it
 * on the {@code order-placed} topic, which fulfilment receives, and to the optional features' hooks.
 */
@Service
public class OrderService {

    /** Customer ids become blob keys and document partitions, so they are kept plain. */
    private static final Pattern CUSTOMER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final OrderRepository orders;
    private final Publisher placed;
    private final Environment environment;
    private final ObjectProvider<IdempotencyKeys> idempotencyKeys;
    private final List<OrderPlacedHook> hooks;

    OrderService(OrderRepository orders, @Qualifier("order-placed") Publisher placed, Environment environment,
                 ObjectProvider<IdempotencyKeys> idempotencyKeys, List<OrderPlacedHook> hooks) {
        this.orders = orders;
        this.placed = placed;
        this.environment = environment;
        this.idempotencyKeys = idempotencyKeys;
        this.hooks = hooks;
    }

    /** A placed order, and whether it was placed by an earlier request with the same idempotency key. */
    public record Placement(Order order, boolean replayed) {
    }

    public Placement place(String customerId, List<OrderLine> lines, String idempotencyKey) {
        validate(customerId, lines);
        String orderId = newOrderId();
        IdempotencyKeys keys = idempotencyKey == null ? null : idempotencyKeys.getIfAvailable();
        if (keys != null) {
            Optional<String> earlier = keys.claim(customerId, idempotencyKey, orderId);
            if (earlier.isPresent()) {
                Order order = find(customerId, earlier.get()).orElseThrow(() -> new OrderConflictException(
                        "an order with idempotency key " + idempotencyKey + " is still being placed; retry"));
                return new Placement(order, true);
            }
        }
        BigDecimal total = lines.stream()
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        Order order;
        try {
            order = orders.save(new Order(customerId, orderId, OrderStatus.PLACED, Instant.now(), List.copyOf(lines),
                    total, currency(), null, null, null, null));
        } catch (RuntimeException e) {
            if (keys != null) {
                keys.release(customerId, idempotencyKey);
            }
            throw e;
        }
        // Stored, then announced: a failure in between leaves the order PLACED until the client retries with its
        // idempotency key, which a production service would close with an outbox.
        placed.publish(OutgoingMessage.builder(OrderMessages.placed(order)).attribute("type", "order.placed").build());
        hooks.forEach(hook -> hook.placed(order));
        return new Placement(order, false);
    }

    public Optional<Order> find(String customerId, String orderId) {
        return orders.findById(Key.of(customerId, orderId));
    }

    public List<Order> list(String customerId) {
        return orders.findByCustomerIdOrderByOrderIdDesc(customerId);
    }

    /** Read on every order, so a value reloaded from the {@code settings} parameter store applies at once. */
    int maxLines() {
        return environment.getProperty("orders.max-lines", Integer.class, 20);
    }

    String currency() {
        return environment.getProperty("orders.currency", "GBP");
    }

    private void validate(String customerId, List<OrderLine> lines) {
        if (!CUSTOMER_ID.matcher(customerId).matches()) {
            throw new InvalidOrderException("customer ids are 1 to 64 letters, digits and dashes");
        }
        if (lines == null || lines.isEmpty()) {
            throw new InvalidOrderException("an order needs at least one line");
        }
        if (lines.size() > maxLines()) {
            throw new InvalidOrderException("an order has at most " + maxLines() + " lines");
        }
        for (OrderLine line : lines) {
            if (line.sku() == null || line.sku().isBlank() || line.quantity() < 1 || line.unitPrice() == null
                    || line.unitPrice().signum() < 0) {
                throw new InvalidOrderException("each line needs a sku, a positive quantity and a unit price");
            }
        }
    }

    /** Milliseconds since the epoch, fixed width so ids sort by time, and a random suffix. */
    private static String newOrderId() {
        byte[] suffix = new byte[4];
        ThreadLocalRandom.current().nextBytes(suffix);
        return "%013d-%s".formatted(System.currentTimeMillis(), HexFormat.of().formatHex(suffix));
    }
}
