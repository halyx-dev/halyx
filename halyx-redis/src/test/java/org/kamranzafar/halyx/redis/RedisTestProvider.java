/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.halyx.redis;

import org.kamranzafar.halyx.cache.CacheConfig;
import org.kamranzafar.halyx.cache.spi.CacheDriver;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.spi.CloudProvider;
import org.kamranzafar.halyx.spi.ProviderConnection;

import java.util.function.Function;

/** A minimal provider exposing the shared Redis driver, as the cloud modules do. */
final class RedisTestProvider implements CloudProvider {

    private final Function<org.kamranzafar.halyx.cache.CacheAuth.Iam, RedisCredentialsSource> iam;

    RedisTestProvider(Function<org.kamranzafar.halyx.cache.CacheAuth.Iam, RedisCredentialsSource> iam) {
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
