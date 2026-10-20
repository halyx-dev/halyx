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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The suite on the {@code local} profile: every service in memory, with every feature but SQL, which has no stub. */
class LocalDeploymentTest extends PortableOrdersSuite {

    private static InProcessService service;

    @BeforeAll
    static void start() {
        service = new InProcessService(List.of("local", "events", "streams", "cache"),
                Map.of("orders.local.signing-key", "local-test-key"));
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
        }
    }

    @Override
    protected OrdersClient client() {
        return service.client();
    }

    @Override
    protected Expectations expected() {
        return new Expectations("local", Set.of("events", "streams", "cache"), 20, "GBP", "local-test-key",
                Duration.ofSeconds(20));
    }
}
