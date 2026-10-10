package org.kamranzafar.cirrus.redis;

import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;

import java.util.function.Function;

/** A minimal provider exposing the shared Redis driver, as the cloud modules do. */
final class RedisTestProvider implements CloudProvider {

    private final Function<org.kamranzafar.cirrus.cache.CacheAuth.Iam, RedisCredentialsSource> iam;

    RedisTestProvider(Function<org.kamranzafar.cirrus.cache.CacheAuth.Iam, RedisCredentialsSource> iam) {
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
