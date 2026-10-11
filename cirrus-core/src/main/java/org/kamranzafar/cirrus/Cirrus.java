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
package org.kamranzafar.cirrus;

import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.interceptor.Interceptor;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.spi.CloudProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entry point to Cirrus: a Facade and a Registry of named provider contexts. Several clouds and
 * accounts can be used side by side:
 *
 * <pre>{@code
 * try (Cirrus cirrus = Cirrus.builder()
 *         .register("orders", ProviderConfig.of("aws").region("eu-west-2"))
 *         .register("archive", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme"))
 *         .build()) {
 *     BlobStore orders = cirrus.provider("orders").blobStore("orders-bucket");
 * }
 * }</pre>
 *
 * Providers are found on the classpath with {@link ServiceLoader}. Typically one instance lives for
 * the whole application, for example as a Spring bean.
 */
public final class Cirrus implements AutoCloseable {

    private final Map<String, CloudContext> contexts;

    private Cirrus(Map<String, CloudContext> contexts) {
        this.contexts = Collections.unmodifiableMap(contexts);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The registered context named {@code name}. */
    public CloudContext provider(String name) {
        CloudContext context = contexts.get(name);
        if (context == null) {
            throw new NoSuchElementException("No provider registered as '" + name + "'. Registered: " + contexts.keySet());
        }
        return context;
    }

    public Set<String> names() {
        return contexts.keySet();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (CloudContext context : contexts.values()) {
            try {
                context.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static final class Builder {

        private final Map<String, ProviderConfig> registrations = new LinkedHashMap<>();
        private final List<Interceptor> interceptors = new ArrayList<>();
        private final List<CloudProvider> explicitProviders = new ArrayList<>();
        private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        private Builder() {
        }

        /** Registers a provider configuration under a logical name. */
        public Builder register(String name, ProviderConfig config) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(config, "config");
            if (registrations.putIfAbsent(name, config) != null) {
                throw new IllegalArgumentException("Duplicate provider name: " + name);
            }
            return this;
        }

        /** Adds an interceptor applied to every operation. The first added runs outermost. */
        public Builder interceptor(Interceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /** Adds a provider plugin directly, bypassing {@link ServiceLoader} discovery. */
        public Builder plugin(CloudProvider provider) {
            explicitProviders.add(Objects.requireNonNull(provider, "provider"));
            return this;
        }

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
            return this;
        }

        public Cirrus build() {
            Map<String, CloudProvider> plugins = discoverPlugins();
            InterceptorChain chain = new InterceptorChain(interceptors);
            Map<String, CloudContext> contexts = new LinkedHashMap<>();
            registrations.forEach((name, config) -> {
                CloudProvider plugin = plugins.get(config.providerId());
                if (plugin == null) {
                    throw new IllegalStateException("No Cirrus provider '" + config.providerId() + "' on the classpath (for '"
                            + name + "'). Available: " + new TreeSet<>(plugins.keySet())
                            + ". Add the cirrus-" + config.providerId() + " dependency.");
                }
                contexts.put(name, new CloudContext(name, config, plugin, chain));
            });
            return new Cirrus(contexts);
        }

        private Map<String, CloudProvider> discoverPlugins() {
            Map<String, CloudProvider> plugins = new LinkedHashMap<>();
            for (CloudProvider provider : ServiceLoader.load(CloudProvider.class, classLoader)) {
                plugins.putIfAbsent(provider.id(), provider);
            }
            explicitProviders.forEach(p -> plugins.put(p.id(), p));
            return plugins;
        }
    }
}
