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
package dev.halyx.azure;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.events.EventBusConfig;
import dev.halyx.messaging.SubscriptionConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Event Grid access keys: plain values used as given, suppliers read when the client is built and refreshed after. */
class AzureSuppliedKeysTest {

    private static final String ENDPOINT = "https://orders.westeurope-1.eventgrid.azure.net";

    @Test
    void eventGridKeysAreReadWhenTheClientIsBuilt() {
        AtomicInteger asked = new AtomicInteger();
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build()) {
            halyx.provider("azure").eventPublisher(EventBusConfig.builder("orders")
                    .property(AzureProperties.EVENT_GRID_ENDPOINT, ENDPOINT)
                    .property(AzureProperties.EVENT_GRID_ACCESS_KEY, () -> "key-" + asked.incrementAndGet()).build());
            halyx.provider("azure").subscription(SubscriptionConfig.builder("inbox").topic("orders")
                    .property(AzureProperties.EVENT_GRID_ENDPOINT, ENDPOINT)
                    .property(AzureProperties.EVENT_GRID_ACCESS_KEY, () -> "key-" + asked.incrementAndGet()).build());

            assertThat(asked).hasValue(2);
        }
    }

    @Test
    void plainEventGridKeysAreUsedAsGiven() {
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build()) {
            assertThat(halyx.provider("azure").eventPublisher(EventBusConfig.builder("orders")
                    .property(AzureProperties.EVENT_GRID_ENDPOINT, ENDPOINT)
                    .property(AzureProperties.EVENT_GRID_ACCESS_KEY, "plain-key").build()).bus()).isEqualTo("orders");
            assertThat(halyx.provider("azure").subscription(SubscriptionConfig.builder("inbox").topic("orders")
                    .property(AzureProperties.EVENT_GRID_ENDPOINT, ENDPOINT)
                    .property(AzureProperties.EVENT_GRID_ACCESS_KEY, "plain-key").build()).name()).isEqualTo("inbox");
        }
    }

    @Test
    void aMissingKeyFailsWhenTheClientIsBuilt() {
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build()) {
            assertThatThrownBy(() -> halyx.provider("azure").eventPublisher(EventBusConfig.builder("orders")
                    .property(AzureProperties.EVENT_GRID_ENDPOINT, ENDPOINT)
                    .property(AzureProperties.EVENT_GRID_ACCESS_KEY, () -> {
                        throw new IllegalStateException("no secret named orders-events-key");
                    }).build())).hasStackTraceContaining("orders-events-key");
        }
    }
}
