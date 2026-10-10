package org.kamranzafar.cirrus.spring.autoconfigure.health;

import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/** UP when the store answers an authenticated read (of a probe name that need not exist). */
public class ParameterStoreHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE = "cirrus-health-probe";

    private final ParameterStore store;

    public ParameterStoreHealthIndicator(ParameterStore store) {
        super("Parameter store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider()).withDetail("kind", store.kind().name().toLowerCase());
        store.get(PROBE);
        builder.up();
    }
}
