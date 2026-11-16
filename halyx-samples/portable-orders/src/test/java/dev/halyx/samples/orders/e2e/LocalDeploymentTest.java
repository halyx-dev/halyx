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

import dev.halyx.Halyx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The suite on the {@code local} profile: every service in memory, with every feature but SQL, which has no stub; then
 * again with {@code keys} on, sealing with a memory key.
 */
class LocalDeploymentTest extends PortableOrdersSuite {

    private static final List<String> PROFILES = List.of("local", "events", "streams", "cache");
    private static final Map<String, String> PROPERTIES = Map.of("orders.local.signing-key", "local-test-key");
    private static final Set<String> FEATURES = Set.of("events", "streams", "cache");

    private static InProcessService service;

    @BeforeAll
    static void start() {
        service = new InProcessService(PROFILES, PROPERTIES);
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
        return expectations(FEATURES);
    }

    @Override
    protected Optional<StoredData> stored() {
        return Optional.of(new StoredData(service.bean(Halyx.class).provider("local")));
    }

    private static Expectations expectations(Set<String> features) {
        return new Expectations("local", features, 20, "GBP", "local-test-key", Set.of(), Duration.ofSeconds(20));
    }

    @Nested
    class WithKeys extends PortableOrdersSuite {

        private static InProcessService keyed;

        @BeforeAll
        static void start() {
            keyed = new InProcessService(Features.with(PROFILES, "keys"), PROPERTIES);
        }

        @AfterAll
        static void stop() {
            if (keyed != null) {
                keyed.close();
            }
        }

        @Override
        protected OrdersClient client() {
            return keyed.client();
        }

        @Override
        protected Expectations expected() {
            return expectations(Features.with(FEATURES, "keys"));
        }

        @Override
        protected Optional<StoredData> stored() {
            return Optional.of(new StoredData(keyed.bean(Halyx.class).provider("local")));
        }
    }
}
