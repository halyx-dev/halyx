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

import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import org.springframework.boot.context.config.ConfigDataResource;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** One {@code halyx:<store>[/<prefix>]} import, resolved to its provider and store configuration. */
public final class HalyxConfigDataResource extends ConfigDataResource {

    private final String location;
    private final String providerName;
    private final ProviderConfig providerConfig;
    private final ParameterStoreConfig storeConfig;
    private final String subPrefix;
    private final String storeName;
    private final boolean expandJson;
    private final Duration reloadInterval;
    private final int startupAttempts;

    HalyxConfigDataResource(String location, String storeName, String providerName, ProviderConfig providerConfig,
                              ParameterStoreConfig storeConfig, String subPrefix, boolean optional, boolean expandJson,
                              Duration reloadInterval, int startupAttempts) {
        super(optional);
        this.location = location;
        this.storeName = storeName;
        this.providerName = providerName;
        this.providerConfig = providerConfig;
        this.storeConfig = storeConfig;
        this.subPrefix = subPrefix;
        this.expandJson = expandJson;
        this.reloadInterval = reloadInterval;
        this.startupAttempts = startupAttempts;
    }

    /** An import with {@code halyx.config-import.enabled=false}: it loads nothing and connects nowhere. */
    static HalyxConfigDataResource switchedOff(String location, boolean optional) {
        return new HalyxConfigDataResource(location, null, null, null, null, "", optional, false, null, 1);
    }

    boolean isSwitchedOff() {
        return providerName == null;
    }

    public String storeName() {
        return storeName;
    }

    boolean expandJson() {
        return expandJson;
    }

    Optional<Duration> reloadInterval() {
        return Optional.ofNullable(reloadInterval);
    }

    int startupAttempts() {
        return startupAttempts;
    }

    public String providerName() {
        return providerName;
    }

    ProviderConfig providerConfig() {
        return providerConfig;
    }

    public ParameterStoreConfig storeConfig() {
        return storeConfig;
    }

    public String subPrefix() {
        return subPrefix;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HalyxConfigDataResource r && r.location.equals(location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location);
    }

    @Override
    public String toString() {
        return location;
    }
}
