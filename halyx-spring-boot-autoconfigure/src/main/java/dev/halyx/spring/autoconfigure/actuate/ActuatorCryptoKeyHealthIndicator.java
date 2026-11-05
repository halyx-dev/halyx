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
package dev.halyx.spring.autoconfigure.actuate;

import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyMetadata;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * UP when the key exists, is enabled, and fits its configured purpose and algorithm. Reads the key's metadata, which
 * the key keeps for a few minutes, so frequent health probes don't spend the key service's request quota; a key
 * disabled or deleted since shows within that time.
 */
public class ActuatorCryptoKeyHealthIndicator extends AbstractHealthIndicator {

    private final CryptoKey key;

    public ActuatorCryptoKeyHealthIndicator(CryptoKey key) {
        super("Key health check failed");
        this.key = key;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", key.provider()).withDetail("name", key.name());
        KeyMetadata metadata = key.metadata(); // fails for a missing key, or one whose type doesn't fit
        builder.withDetail("keyId", metadata.keyId()).withDetail("enabled", metadata.enabled());
        metadata.currentVersion().ifPresent(version -> builder.withDetail("version", version));
        if (metadata.enabled()) {
            builder.up();
        } else {
            builder.down();
        }
    }
}
