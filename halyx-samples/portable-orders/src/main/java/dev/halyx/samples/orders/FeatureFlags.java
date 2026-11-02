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
package dev.halyx.samples.orders;

import dev.halyx.parameters.ParameterStore;
import org.kamranzafar.flipjack.OverridableFeatureToggles;
import org.kamranzafar.flipjack.PollingFeatureToggles;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feature flags, held in the {@code settings} parameter store (SSM Parameter Store, App Configuration, Parameter
 * Manager or Secret Manager) under {@value #PREFIX}, such as {@code features/bulk-discount = true}, and evaluated by
 * flipjack. Flipjack polls the store, so a change takes effect within {@code orders.feature-flags.poll-interval}
 * without a restart. A flag that is missing is off, so every feature starts off until the store turns it on.
 * <p>
 * Overrides sit on top of the store: {@code override("bulk-discount", false)} turns a feature off at once, even while
 * the store is unreachable, and {@code clearOverride} hands it back to the store.
 */
@Configuration(proxyBeanMethods = false)
class FeatureFlags {

    static final String PREFIX = "features";
    static final String BULK_DISCOUNT = "bulk-discount";

    @Bean
    OverridableFeatureToggles featureToggles(@Qualifier("settings") ParameterStore settings,
                                             @Value("${orders.feature-flags.poll-interval:30s}") Duration interval) {
        return PollingFeatureToggles.builder(() -> {
                    Map<String, String> flags = new LinkedHashMap<>();
                    settings.getAll(PREFIX).forEach((name, parameter) ->
                            flags.put(name.substring(name.lastIndexOf('/') + 1), parameter.value()));
                    return flags;
                })
                .interval(interval)
                .requireInitialLoad()     // a store that can't be read stops the start, as the settings import does
                .build()
                .withOverrides();         // kill switches that win over the store
    }
}
