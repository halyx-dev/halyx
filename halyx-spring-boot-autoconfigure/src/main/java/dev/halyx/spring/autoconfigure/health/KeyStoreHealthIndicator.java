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

import dev.halyx.keys.KeyStore;
import dev.halyx.spring.autoconfigure.KeyStorePing;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * UP when the key service answers an authenticated read, past every cache. The answer stands for
 * {@link KeyStorePing#TTL}, so frequent probes don't spend the key service's request quota.
 */
public class KeyStoreHealthIndicator extends AbstractHealthIndicator {

    private final KeyStore store;
    private final KeyStorePing ping;

    public KeyStoreHealthIndicator(KeyStore store) {
        super("Key store health check failed");
        this.store = store;
        this.ping = new KeyStorePing(store);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider());
        store.location().ifPresent(location -> builder.withDetail("location", location));
        ping.check();
        builder.up();
    }
}
