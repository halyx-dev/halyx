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
package dev.halyx.samples.orders.e2e;

import dev.halyx.samples.orders.ReceiptService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The end-to-end tests of the orders service, over its HTTP API only. Every deployment runs the same tests: in
 * process on the memory provider and on each cloud's emulators, the built image, and the image on real clouds. A
 * feature that a deployment doesn't turn on is skipped, so the test report lists what each cloud ran.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
public abstract class PortableOrdersSuite {

    /** Each test uses its own customers, so runs and tests never see each other's orders. */
    private final String run = "e2e-" + UUID.randomUUID().toString().substring(0, 8);

    protected abstract OrdersClient client();

    protected abstract Expectations expected();

    @Test
    void describesItsDeploymentAndSettings() {
        Map<String, Object> deployment = client().get("/deployment").json();

        assertThat(deployment.get("cloud")).isEqualTo(expected().cloud());
        assertThat(new HashSet<>(strings(deployment.get("features")))).isEqualTo(expected().features());
        @SuppressWarnings("unchecked")
        Map<String, Object> providers = (Map<String, Object>) deployment.get("providers");
        assertThat(providers).containsKeys("receipts", "orders", "order-placed", "fulfilment", "settings", "secrets");
        assertThat(new HashSet<>(providers.values())).as("every Halyx bean on one provider").hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) deployment.get("settings");
        assertThat(((Number) settings.get("maxLines")).intValue()).isEqualTo(expected().maxLines());
        assertThat(settings.get("currency")).isEqualTo(expected().currency());
    }

    @Test
    void healthIsUp() {
        OrdersClient.Response health = client().get("/actuator/health");

        assertThat(health.status()).as(health.body()).isEqualTo(200);
        assertThat(health.json().get("status")).isEqualTo("UP");
    }

    @Test
    void placesAnOrderAndFulfilmentConfirmsItWithASignedReceipt() {
        String customer = customer("ada");
        OrdersClient.Response placed = place(customer, null, line("tea", 2, "3.50"), line("cake", 1, "4.25"));

        assertThat(placed.status()).as(placed.body()).isEqualTo(201);
        Map<String, Object> order = placed.json();
        String orderId = (String) order.get("orderId");
        assertThat(order.get("status")).isEqualTo("PLACED");
        assertThat(new BigDecimal(order.get("total").toString())).isEqualByComparingTo("11.25");
        assertThat(order.get("currency")).isEqualTo(expected().currency());
        assertThat(placed.headers().get("location")).containsExactly("/customers/" + customer + "/orders/" + orderId);

        Map<String, Object> confirmed = awaitOrder(customer, orderId, o -> "CONFIRMED".equals(o.get("status")));
        assertThat(confirmed.get("receipt")).isEqualTo(true);

        OrdersClient.Response receipt = client().get(orderPath(customer, orderId) + "/receipt");
        assertThat(receipt.status()).isEqualTo(200);
        assertThat(receipt.body()).contains("Receipt for order " + orderId, "2 x tea @ 3.50",
                "Total: 11.25 " + expected().currency());
        int signature = receipt.body().indexOf("Signature: ");
        assertThat(signature).isPositive();
        assertThat(receipt.body().substring(signature + "Signature: ".length()).trim())
                .as("signed with the secret store's key")
                .isEqualTo(ReceiptService.sign(receipt.body().substring(0, signature), expected().signingKey()));
    }

    @Test
    void listsACustomersOrdersNewestFirst() {
        String customer = customer("grace");
        String first = (String) place(customer, null, line("tea", 1, "1.00")).json().get("orderId");
        String second = (String) place(customer, null, line("tea", 2, "1.00")).json().get("orderId");

        List<Map<String, Object>> orders = client().get("/customers/" + customer + "/orders").jsonList();

        assertThat(orders).extracting(o -> o.get("orderId")).containsExactly(second, first);
        assertThat(client().get(orderPath(customer, "0000000000000-missing")).status()).isEqualTo(404);
    }

    @Test
    void rejectsOrdersOverTheConfiguredLineLimit() {
        String customer = customer("edsger");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i <= expected().maxLines(); i++) {
            lines.add(line("sku-" + i, 1, "1.00"));
        }

        OrdersClient.Response tooMany = client().post("/customers/" + customer + "/orders", Map.of("lines", lines), null);
        OrdersClient.Response justRight = client().post("/customers/" + customer + "/orders",
                Map.of("lines", lines.subList(0, expected().maxLines())), null);

        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.json().get("detail")).isEqualTo("an order has at most " + expected().maxLines() + " lines");
        assertThat(justRight.status()).isEqualTo(201);
        assertThat(client().post("/customers/" + customer + "/orders", Map.of("lines", List.of()), null).status())
                .isEqualTo(400);
    }

    @Test
    void eventsNotifyThroughTheEventBus() {
        assumeTrue(expected().has("events"), "events are off in this deployment");
        String customer = customer("barbara");
        String orderId = (String) place(customer, null, line("tea", 1, "2.00")).json().get("orderId");

        awaitOrder(customer, orderId, o -> Boolean.TRUE.equals(o.get("notified")));
    }

    @Test
    void streamsAuditEachOrder() {
        assumeTrue(expected().has("streams"), "streams are off in this deployment");
        String customer = customer("leslie");
        String orderId = (String) place(customer, null, line("tea", 1, "2.00")).json().get("orderId");

        awaitOrder(customer, orderId, o -> Boolean.TRUE.equals(o.get("audited")));
    }

    @Test
    void cacheMakesAnIdempotencyKeyReplayTheOrder() {
        assumeTrue(expected().has("cache"), "the cache is off in this deployment");
        String customer = customer("tony");
        String key = UUID.randomUUID().toString();

        OrdersClient.Response first = place(customer, key, line("tea", 3, "1.10"));
        OrdersClient.Response retry = place(customer, key, line("tea", 3, "1.10"));
        OrdersClient.Response other = place(customer, UUID.randomUUID().toString(), line("tea", 3, "1.10"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().get("orderId")).isEqualTo(first.json().get("orderId"));
        assertThat(other.status()).isEqualTo(201);
        assertThat(client().get("/customers/" + customer + "/orders").jsonList()).hasSize(2);
    }

    @Test
    void sqlRecordsConfirmedOrdersInTheLedger() {
        assumeTrue(expected().has("sql"), "SQL is off in this deployment");
        String customer = customer("niklaus");
        String orderId = (String) place(customer, null, line("tea", 4, "2.50")).json().get("orderId");
        awaitOrder(customer, orderId, o -> "CONFIRMED".equals(o.get("status")));

        List<Map<String, Object>> entries = client().get("/customers/" + customer + "/ledger").jsonList();

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("orderId")).isEqualTo(orderId);
        assertThat(new BigDecimal(entries.getFirst().get("total").toString())).isEqualByComparingTo("10.00");
        assertThat(entries.getFirst().get("currency")).isEqualTo(expected().currency());
    }

    @Test
    void featuresThatAreOffHaveNoEndpoints() {
        assumeTrue(!expected().has("sql"), "SQL is on in this deployment");

        assertThat(client().get("/customers/" + customer("alan") + "/ledger").status()).isEqualTo(404);
    }

    // -----------------------------------------------------------------------------------------------------------------

    private String customer(String name) {
        return run + "-" + name;
    }

    private static String orderPath(String customer, String orderId) {
        return "/customers/" + customer + "/orders/" + orderId;
    }

    private static Map<String, Object> line(String sku, int quantity, String unitPrice) {
        return Map.of("sku", sku, "quantity", quantity, "unitPrice", new BigDecimal(unitPrice));
    }

    @SafeVarargs
    private OrdersClient.Response place(String customer, String idempotencyKey, Map<String, Object>... lines) {
        return client().post("/customers/" + customer + "/orders", Map.of("lines", List.of(lines)), idempotencyKey);
    }

    private Map<String, Object> awaitOrder(String customer, String orderId, Predicate<Map<String, Object>> condition) {
        return await(() -> client().get(orderPath(customer, orderId)).json(), condition,
                "order " + orderId + " to satisfy the condition");
    }

    private <T> T await(Supplier<T> read, Predicate<T> condition, String what) {
        Instant deadline = Instant.now().plus(expected().patience());
        T last = read.get();
        while (!condition.test(last)) {
            assertThat(Instant.now()).as("waited %s for %s; last saw %s", expected().patience(), what, last)
                    .isBefore(deadline);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            last = read.get();
        }
        return last;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object list) {
        return (List<String>) list;
    }
}
