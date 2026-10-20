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

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The suite against a running deployment, such as the image on a real cloud. Runs only with
 * {@code -Dorders.e2e.url=http://...}; the deployment's expected cloud, features, settings and signing key come from
 * {@code orders.e2e.cloud}, {@code .features} (comma separated), {@code .max-lines}, {@code .currency},
 * {@code .signing-key} and {@code .patience} (an ISO-8601 duration, 2 minutes by default).
 */
@EnabledIfSystemProperty(named = "orders.e2e.url", matches = ".+")
class DeployedServiceIT extends PortableOrdersSuite {

    private final OrdersClient client = new OrdersClient(URI.create(System.getProperty("orders.e2e.url")));

    @Override
    protected OrdersClient client() {
        return client;
    }

    @Override
    protected Expectations expected() {
        Set<String> features = Arrays.stream(System.getProperty("orders.e2e.features", "").split(","))
                .map(String::trim).filter(feature -> !feature.isEmpty()).collect(Collectors.toSet());
        return new Expectations(System.getProperty("orders.e2e.cloud"), features,
                Integer.parseInt(System.getProperty("orders.e2e.max-lines", "20")),
                System.getProperty("orders.e2e.currency", "GBP"),
                System.getProperty("orders.e2e.signing-key"),
                Duration.parse(System.getProperty("orders.e2e.patience", "PT2M")));
    }
}
