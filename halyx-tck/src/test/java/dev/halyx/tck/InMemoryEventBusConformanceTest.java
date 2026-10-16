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
package dev.halyx.tck;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.messaging.SubscriptionConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Runs the event bus kit against the in-memory provider, which also checks the kit itself. */
class InMemoryEventBusConformanceTest extends EventBusConformanceTest {

    private static Halyx halyx;
    private static Subscription inbox;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        inbox = halyx.provider("test").subscription(SubscriptionConfig.builder("events-inbox").topic("events").build());
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Override
    protected EventPublisher publisher() {
        return halyx.provider("test").eventPublisher("events");
    }

    @Override
    protected Subscription inbox() {
        return inbox;
    }
}
