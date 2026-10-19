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

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the beans of {@code halyx.*} entries, each named after its key, and fails at startup with the
 * conflicting properties named when a key is already a bean name: another entry's, in any group, or a bean the
 * application or Halyx registered earlier. Without this, Boot's disabled bean overriding reports only the name.
 */
final class HalyxBeanNames {

    private final BeanDefinitionRegistry registry;
    private final Map<String, String> properties = new HashMap<>();

    HalyxBeanNames(BeanDefinitionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registers {@code definition} as {@code name}, the key of the entry {@code property}; {@code keepName} says how
     * the entry keeps its cloud resource's name under a new key, such as {@code bucket}.
     */
    void register(String name, String property, String keepName, BeanDefinition definition) {
        String other = properties.get(name);
        String owner = other != null ? other : registry.containsBeanDefinition(name) ? existing(name) : null;
        if (owner != null) {
            throw new IllegalStateException(property + ": the bean name '" + name + "' is already used by " + owner
                    + ". Rename the key, which is the bean name" + (keepName == null ? ""
                    : ", and set " + property + "." + keepName + " to keep the cloud resource's current name"));
        }
        properties.put(name, property);
        registry.registerBeanDefinition(name, definition);
    }

    private String existing(String name) {
        String source = registry.getBeanDefinition(name).getResourceDescription();
        return "bean '" + name + "'" + (source == null ? "" : " from " + source);
    }
}
