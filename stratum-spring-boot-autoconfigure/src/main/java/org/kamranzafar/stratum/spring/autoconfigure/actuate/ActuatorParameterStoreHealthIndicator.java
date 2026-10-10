package org.kamranzafar.stratum.spring.autoconfigure.actuate;

import org.kamranzafar.stratum.parameters.ParameterStore;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/** UP when the store answers an authenticated read (of a probe name that need not exist). */
public class ActuatorParameterStoreHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE = "stratum-health-probe";

    private final ParameterStore store;

    public ActuatorParameterStoreHealthIndicator(ParameterStore store) {
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
