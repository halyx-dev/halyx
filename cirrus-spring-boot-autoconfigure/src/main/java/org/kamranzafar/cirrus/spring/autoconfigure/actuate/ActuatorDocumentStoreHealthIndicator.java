package org.kamranzafar.cirrus.spring.autoconfigure.actuate;

import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.Key;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/** UP when the table answers an authenticated read (of a probe key that need not exist). */
public class ActuatorDocumentStoreHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE = "cirrus-health-probe";

    private final DocumentStore store;

    public ActuatorDocumentStoreHealthIndicator(DocumentStore store) {
        super("Document store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider()).withDetail("table", store.table().name());
        store.get(store.table().hasSortKey() ? Key.of(PROBE, PROBE) : Key.of(PROBE));
        builder.up();
    }
}
