package org.kamranzafar.stratum.redis;

import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.spi.CacheDriver;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.spi.CloudProvider;
import org.kamranzafar.stratum.spi.ProviderConnection;

import java.util.function.Function;

/** A minimal provider exposing the shared Redis driver, as the cloud modules do. */
final class RedisTestProvider implements CloudProvider {

    private final Function<org.kamranzafar.stratum.cache.CacheAuth.Iam, RedisCredentialsSource> iam;

    RedisTestProvider(Function<org.kamranzafar.stratum.cache.CacheAuth.Iam, RedisCredentialsSource> iam) {
        this.iam = iam;
    }

    @Override
    public String id() {
        return "redis-test";
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        RedisCaches caches = new RedisCaches(id());
        return new ProviderConnection() {
            @Override
            public CacheDriver cache(CacheConfig cacheConfig) {
                return caches.driver(cacheConfig, RedisCaches.credentials(cacheConfig.auth(), iam));
            }

            @Override
            public void close() {
                caches.close();
            }
        };
    }
}
