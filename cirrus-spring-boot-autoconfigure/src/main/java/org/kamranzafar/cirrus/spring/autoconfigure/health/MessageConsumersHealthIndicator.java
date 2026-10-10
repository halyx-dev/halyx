package org.kamranzafar.cirrus.spring.autoconfigure.health;

import org.kamranzafar.cirrus.messaging.ConsumerStatistics;
import org.kamranzafar.cirrus.spring.messaging.CirrusListenerRegistrar;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code @CirrusListener} consumers: UP while all of them run, DOWN if one has stopped while the
 * application is running. Each consumer's counters are reported as details.
 */
public class MessageConsumersHealthIndicator extends AbstractHealthIndicator {

    private final CirrusListenerRegistrar registrar;

    public MessageConsumersHealthIndicator(CirrusListenerRegistrar registrar) {
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
