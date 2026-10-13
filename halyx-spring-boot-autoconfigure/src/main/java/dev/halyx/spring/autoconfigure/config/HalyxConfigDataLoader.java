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
package dev.halyx.spring.autoconfigure.config;

import dev.halyx.Halyx;
import dev.halyx.interceptor.RetryInterceptor;
import dev.halyx.interceptor.RetryPolicy;
import dev.halyx.parameters.ParameterStore;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;

import java.time.Duration;
import java.util.List;

/**
 * Loads a parameter store into the Spring {@code Environment} at startup: every value becomes a
 * property whose key is its name with {@code /} replaced by {@code .} ({@code db/pool/size} becomes
 * {@code db.pool.size}), and JSON object values also add their fields ({@code db.creds.password}).
 * A temporarily unavailable backend is retried with backoff. With a {@code reload-interval}, the
 * {@code HalyxConfigReloader} keeps the properties up to date afterwards.
 */
public class HalyxConfigDataLoader implements ConfigDataLoader<HalyxConfigDataResource> {

    @Override
    public ConfigData load(ConfigDataLoaderContext context, HalyxConfigDataResource resource) {
        try (Halyx halyx = Halyx.builder()
                .classLoader(HalyxConfigDataLoader.class.getClassLoader())
                .register(resource.providerName(), resource.providerConfig())
                .interceptor(new RetryInterceptor(new RetryPolicy(resource.startupAttempts(), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0)))
                .build()) {
            ParameterStore store = halyx.provider(resource.providerName()).parameterStore(resource.storeConfig());
            return new ConfigData(List.of(new HalyxPropertySource(resource, HalyxPropertySource.load(store, resource))));
        }
    }
}
