package org.kamranzafar.cirrus.spring.autoconfigure.health;

import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Spring Boot 4 variant of the blob store health check: UP when the bucket can be listed, which
 * proves connectivity, credentials and that the bucket exists.
 */
public class BlobStoreHealthIndicator extends AbstractHealthIndicator {

    private static final ListOptions PROBE = ListOptions.builder().pageSize(1).build();

    private final BlobStore store;

    public BlobStoreHealthIndicator(BlobStore store) {
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
