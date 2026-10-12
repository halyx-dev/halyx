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
package org.kamranzafar.halyx.spring.autoconfigure.health;

import org.kamranzafar.halyx.messaging.ConsumerStatistics;
import org.kamranzafar.halyx.spring.messaging.HalyxListenerRegistrar;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code @HalyxListener} consumers: UP while all of them run, DOWN if one has stopped while the
 * application is running. Each consumer's counters are reported as details.
 */
public class MessageConsumersHealthIndicator extends AbstractHealthIndicator {

    private final HalyxListenerRegistrar registrar;

    public MessageConsumersHealthIndicator(HalyxListenerRegistrar registrar) {
        super("Message consumer health check failed");
        this.registrar = registrar;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean allRunning = true;
        for (var entry : registrar.consumers().entrySet()) {
            ConsumerStatistics s = entry.getValue().statistics();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("running", entry.getValue().isRunning());
            details.put("received", s.received());
            details.put("acked", s.acked());
            details.put("nacked", s.nacked());
            details.put("failed", s.failed());
            details.put("inFlight", s.inFlight());
            details.put("receiveErrors", s.receiveErrors());
            builder.withDetail(entry.getKey(), details);
            allRunning &= entry.getValue().isRunning();
        }
        if (allRunning || !registrar.isRunning()) {
            builder.up();
        } else {
            builder.down();
        }
    }
}
