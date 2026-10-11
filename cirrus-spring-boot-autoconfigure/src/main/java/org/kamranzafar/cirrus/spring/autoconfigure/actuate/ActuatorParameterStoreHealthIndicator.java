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
package org.kamranzafar.cirrus.spring.autoconfigure.actuate;

import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/** UP when the store answers an authenticated read (of a probe name that need not exist). */
public class ActuatorParameterStoreHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE = "cirrus-health-probe";

    private final ParameterStore store;

    public ActuatorParameterStoreHealthIndicator(ParameterStore store) {
        super("Parameter store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider()).withDetail("kind", store.kind().name().toLowerCase());
        store.get(PROBE);
        builder.up();
    }
}
