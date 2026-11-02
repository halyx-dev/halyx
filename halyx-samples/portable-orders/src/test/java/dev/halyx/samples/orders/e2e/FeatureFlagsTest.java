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

import dev.halyx.parameters.ParameterStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.flipjack.OverridableFeatureToggles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A feature flag changed in the settings store while the service runs: flipjack picks the change up at its next poll,
 * with no restart, and a flag that is deleted is off again. An override wins over the store until it's cleared.
 */
class FeatureFlagsTest {

    private static final String FLAG = "features/bulk-discount";
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static InProcessService service;

    @BeforeAll
    static void start() {
        service = new InProcessService(List.of("local"), Map.of(
                "orders.local.signing-key", "local-test-key",
                "orders.feature-flags.poll-interval", "100ms"));
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void bulkDiscountTurnsOnAndOffWithoutARestart() throws InterruptedException {
        ParameterStore settings = service.bean("settings", ParameterStore.class);

        assertThat(total()).as("missing, so off").isEqualByComparingTo("14.00");

        settings.put(FLAG, "true");
        awaitTotal("13.00");

        settings.put(FLAG, "false");
        awaitTotal("14.00");

        settings.put(FLAG, "true");
        awaitTotal("13.00");
        settings.delete(FLAG);
        awaitTotal("14.00");
    }

    @Test
    void anOverrideWinsOverTheStore() throws InterruptedException {
        ParameterStore settings = service.bean("settings", ParameterStore.class);
        OverridableFeatureToggles toggles = service.bean("featureToggles", OverridableFeatureToggles.class);
        try {
            settings.put(FLAG, "true");
            awaitTotal("13.00");

            toggles.override("bulk-discount", false);
            assertThat(total()).as("overridden off, at once").isEqualByComparingTo("14.00");

            toggles.clearOverride("bulk-discount");
            assertThat(total()).as("back to the store").isEqualByComparingTo("13.00");
        } finally {
            toggles.clearOverrides();
            settings.delete(FLAG);
            awaitTotal("14.00");
        }
    }

    /** Ten teas at 1.00, 10% off when the flag is on, and a cake at 4.00, never discounted. */
    private static BigDecimal total() {
        var placed = service.client().post("/customers/flags-" + System.nanoTime() + "/orders", Map.of("lines", List.of(
                Map.of("sku", "tea", "quantity", 10, "unitPrice", new BigDecimal("1.00")),
                Map.of("sku", "cake", "quantity", 1, "unitPrice", new BigDecimal("4.00")))), null);
        assertThat(placed.status()).as(placed.body()).isEqualTo(201);
        return new BigDecimal(placed.json().get("total").toString());
    }

    private static void awaitTotal(String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        BigDecimal total = total();
        while (total.compareTo(new BigDecimal(expected)) != 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            total = total();
        }
        assertThat(total).isEqualByComparingTo(expected);
    }
}
