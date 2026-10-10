package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.parameters.ParameterStore;
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
 * Keeps {@code cirrus:} imports with a {@code reload-interval} up to date: watches each store and, on
 * changes (including rotations), replaces the imported properties in place and publishes a
 * {@link CirrusParametersChangedEvent}. When Spring Cloud Context is present it also publishes its
 * {@code EnvironmentChangeEvent}, which re-binds {@code @ConfigurationProperties} beans and refreshes
 * {@code @RefreshScope} beans. Without Spring Cloud, values read from the {@code Environment} (and
 * beans reacting to the event) see changes; plain {@code @Value} fields keep their startup values.
 */
public class CirrusConfigReloader implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(CirrusConfigReloader.class);
    private static final String SPRING_CLOUD_EVENT = "org.springframework.cloud.context.environment.EnvironmentChangeEvent";

    private final ConfigurableEnvironment environment;
    private final Cirrus cirrus;
    private final ApplicationContext context;
    private final List<AutoCloseable> watches = new ArrayList<>();
    private volatile boolean running;

    public CirrusConfigReloader(ConfigurableEnvironment environment, Cirrus cirrus, ApplicationContext context) {
        this.environment = environment;
        this.cirrus = cirrus;
        this.context = context;
    }

    @Override
    public void start() {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof CirrusPropertySource imported && imported.resource().reloadInterval().isPresent()) {
                watch(imported);
            }
        }
        running = true;
    }

    private void watch(CirrusPropertySource imported) {
        CirrusConfigDataResource resource = imported.resource();
        ParameterStore store = cirrus.provider(resource.providerName()).parameterStore(resource.storeConfig());
        watches.add(store.watch(resource.subPrefix(), resource.reloadInterval().orElseThrow(), changes -> {
            Set<String> keys = imported.replace(CirrusPropertySource.load(store, resource));
            if (!keys.isEmpty()) {
                LOG.info("Reloaded {} properties from {}", keys.size(), resource);
                context.publishEvent(new CirrusParametersChangedEvent(this, resource.storeName(), keys));
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
