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
package dev.halyx.spring.autoconfigure.health;

import dev.halyx.cache.Cache;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/** UP when the cache server answers an authenticated command. */
public class CacheHealthIndicator extends AbstractHealthIndicator {

    private final Cache<?> cache;

    public CacheHealthIndicator(Cache<?> cache) {
        super("Cache health check failed");
        this.cache = cache;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", cache.provider()).withDetail("namespace", cache.namespace());
        cache.ping(); // fails on an outage even when the cache is fail-open
        builder.up();
    }
}
