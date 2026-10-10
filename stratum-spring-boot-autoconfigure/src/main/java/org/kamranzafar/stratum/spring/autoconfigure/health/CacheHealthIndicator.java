package org.kamranzafar.stratum.spring.autoconfigure.health;

import org.kamranzafar.stratum.cache.Cache;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/** UP when the cache server answers an authenticated command. */
public class CacheHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE_KEY = "stratum:health-probe";

    private final Cache<?> cache;

    public CacheHealthIndicator(Cache<?> cache) {
        super("Cache health check failed");
        this.cache = cache;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", cache.provider()).withDetail("namespace", cache.namespace());
        cache.exists(PROBE_KEY);
        builder.up();
    }
}
