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
package dev.halyx.spring.autoconfigure.actuate;

import dev.halyx.spring.messaging.HalyxListenerRegistrar;
import dev.halyx.stream.ListenerStatistics;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code @HalyxListener} stream listeners: UP while all of them run, DOWN if one has stopped while the
 * application is running. Each listener's counters and owned partitions are reported as details.
 */
public class ActuatorStreamListenersHealthIndicator extends AbstractHealthIndicator {

    private final HalyxListenerRegistrar registrar;

    public ActuatorStreamListenersHealthIndicator(HalyxListenerRegistrar registrar) {
        super("Stream listener health check failed");
        this.registrar = registrar;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean allRunning = true;
        for (var entry : registrar.streamListeners().entrySet()) {
            ListenerStatistics s = entry.getValue().statistics();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("running", entry.getValue().isRunning());
            details.put("assignedPartitions", s.assignedPartitions());
            details.put("received", s.received());
            details.put("handled", s.handled());
            details.put("failed", s.failed());
            details.put("deadLettered", s.deadLettered());
            details.put("inFlight", s.inFlight());
            details.put("pollErrors", s.pollErrors());
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
