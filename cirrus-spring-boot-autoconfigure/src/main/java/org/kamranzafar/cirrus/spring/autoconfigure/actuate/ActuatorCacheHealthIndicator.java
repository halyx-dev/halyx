package org.kamranzafar.cirrus.spring.autoconfigure.actuate;

import org.kamranzafar.cirrus.cache.Cache;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/** UP when the cache server answers an authenticated command. */
public class ActuatorCacheHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE_KEY = "cirrus:health-probe";

    private final Cache<?> cache;

    public ActuatorCacheHealthIndicator(Cache<?> cache) {
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
