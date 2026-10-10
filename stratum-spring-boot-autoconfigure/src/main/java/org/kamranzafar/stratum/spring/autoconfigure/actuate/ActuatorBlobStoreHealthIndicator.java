package org.kamranzafar.stratum.spring.autoconfigure.actuate;

import org.kamranzafar.stratum.storage.BlobStore;
import org.kamranzafar.stratum.storage.ListOptions;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Spring Boot 3 variant of the blob store health check: UP when the bucket can be listed, which
 * proves connectivity, credentials and that the bucket exists.
 */
public class ActuatorBlobStoreHealthIndicator extends AbstractHealthIndicator {

    private static final ListOptions PROBE = ListOptions.builder().pageSize(1).build();

    private final BlobStore store;

    public ActuatorBlobStoreHealthIndicator(BlobStore store) {
        super("Blob store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider()).withDetail("bucket", store.bucket());
        store.list(PROBE);
        builder.up();
    }
}
