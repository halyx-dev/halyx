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

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/customers/{customerId}/orders")
class OrderController {

    record PlaceOrder(List<OrderLine> lines) {
    }

    /** An order as the API shows it; the stored version stays internal. */
    record OrderView(String customerId, String orderId, OrderStatus status, Instant placedAt, List<OrderLine> lines,
                     BigDecimal total, String currency, boolean receipt, boolean notified, boolean audited) {

        static OrderView of(Order order) {
            return new OrderView(order.customerId(), order.orderId(), order.status(), order.placedAt(), order.lines(),
                    order.total(), order.currency(), order.receipt() != null, Boolean.TRUE.equals(order.notified()),
                    Boolean.TRUE.equals(order.audited()));
        }
    }

    private final OrderService orders;
    private final ReceiptService receipts;

    OrderController(OrderService orders, ReceiptService receipts) {
        this.orders = orders;
        this.receipts = receipts;
    }

    /** 201 for a new order; 200 with the earlier order when the {@code Idempotency-Key} was used before. */
    @PostMapping
    ResponseEntity<OrderView> place(@PathVariable String customerId, @RequestBody PlaceOrder request,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderService.Placement placement = orders.place(customerId, request.lines(), idempotencyKey);
        OrderView view = OrderView.of(placement.order());
        if (placement.replayed()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create("/customers/" + customerId + "/orders/" + view.orderId())).body(view);
    }

    @GetMapping
    List<OrderView> list(@PathVariable String customerId) {
        return orders.list(customerId).stream().map(OrderView::of).toList();
    }

    @GetMapping("/{orderId}")
    ResponseEntity<OrderView> get(@PathVariable String customerId, @PathVariable String orderId) {
        return ResponseEntity.of(orders.find(customerId, orderId).map(OrderView::of));
    }

    /** The signed receipt, once fulfilment has written it; 404 until then. */
    @GetMapping(value = "/{orderId}/receipt", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> receipt(@PathVariable String customerId, @PathVariable String orderId) {
        return ResponseEntity.of(receipts.read(customerId, orderId));
    }

    @ExceptionHandler(InvalidOrderException.class)
    ProblemDetail invalid(InvalidOrderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(OrderConflictException.class)
    ProblemDetail conflict(OrderConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
