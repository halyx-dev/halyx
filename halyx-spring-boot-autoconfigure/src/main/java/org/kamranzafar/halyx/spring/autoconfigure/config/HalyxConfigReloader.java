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
package org.kamranzafar.halyx.spring.autoconfigure.config;

import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.parameters.ParameterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps {@code halyx:} imports with a {@code reload-interval} up to date: watches each store and, on
 * changes (including rotations), replaces the imported properties in place and publishes a
 * {@link HalyxParametersChangedEvent}. When Spring Cloud Context is present it also publishes its
 * {@code EnvironmentChangeEvent}, which re-binds {@code @ConfigurationProperties} beans and refreshes
 * {@code @RefreshScope} beans. Without Spring Cloud, values read from the {@code Environment} (and
 * beans reacting to the event) see changes; plain {@code @Value} fields keep their startup values.
 */
public class HalyxConfigReloader implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(HalyxConfigReloader.class);
    private static final String SPRING_CLOUD_EVENT = "org.springframework.cloud.context.environment.EnvironmentChangeEvent";

    private final ConfigurableEnvironment environment;
    private final Halyx halyx;
    private final ApplicationContext context;
    private final List<AutoCloseable> watches = new ArrayList<>();
    private volatile boolean running;

    public HalyxConfigReloader(ConfigurableEnvironment environment, Halyx halyx, ApplicationContext context) {
        this.environment = environment;
        this.halyx = halyx;
        this.context = context;
    }

    @Override
    public void start() {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof HalyxPropertySource imported && imported.resource().reloadInterval().isPresent()) {
                watch(imported);
            }
        }
        running = true;
    }

    private void watch(HalyxPropertySource imported) {
        HalyxConfigDataResource resource = imported.resource();
        ParameterStore store = halyx.provider(resource.providerName()).parameterStore(resource.storeConfig());
        watches.add(store.watch(resource.subPrefix(), resource.reloadInterval().orElseThrow(), changes -> {
            Set<String> keys = imported.replace(HalyxPropertySource.load(store, resource));
            if (!keys.isEmpty()) {
                LOG.info("Reloaded {} properties from {}", keys.size(), resource);
                context.publishEvent(new HalyxParametersChangedEvent(this, resource.storeName(), keys));
                publishSpringCloudEvent(keys);
            }
        }));
    }

    private void publishSpringCloudEvent(Set<String> keys) {
        if (!ClassUtils.isPresent(SPRING_CLOUD_EVENT, getClass().getClassLoader())) {
            return;
        }
        try {
            Object event = ClassUtils.forName(SPRING_CLOUD_EVENT, getClass().getClassLoader())
                    .getConstructor(Object.class, Set.class).newInstance(context, keys);
            context.publishEvent((ApplicationEvent) event);
        } catch (ReflectiveOperationException e) {
            LOG.warn("Could not publish Spring Cloud's EnvironmentChangeEvent", e);
        }
    }

    @Override
    public void stop() {
        for (AutoCloseable watch : watches) {
            try {
                watch.close();
            } catch (Exception e) {
                LOG.debug("Stopping a parameter watch failed", e);
            }
        }
        watches.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
