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
package dev.halyx.spring.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.Environment;

/**
 * Warns at startup about Spring Boot properties that have no effect because a Halyx bean replaced the one they
 * configure, such as {@code spring.datasource.*} next to {@code halyx.databases}. Internal to Halyx' auto-configuration.
 */
public final class IgnoredSpringProperties {

    private static final Logger LOG = LoggerFactory.getLogger(IgnoredSpringProperties.class);

    private IgnoredSpringProperties() {
    }

    /** Logs a warning when any {@code prefix.*} property is set; {@code because} says why it is ignored. */
    public static void warnIfSet(Environment environment, String prefix, String because) {
        if (isSet(environment, prefix)) {
            LOG.warn("{}.* is set but ignored: {}", prefix, because);
        }
    }

    static boolean isSet(Environment environment, String prefix) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(prefix);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
                return true;
            }
        }
        return false;
    }
}
