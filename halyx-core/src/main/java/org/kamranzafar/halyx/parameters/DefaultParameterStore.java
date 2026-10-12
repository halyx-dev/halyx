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
package org.kamranzafar.halyx.parameters;

import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.interceptor.InterceptorChain;
import org.kamranzafar.halyx.interceptor.OperationContext;
import org.kamranzafar.halyx.parameters.spi.ParameterDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The portable {@link ParameterStore}: name validation, prefixing, a read cache, interceptors and
 * exception translation around a provider {@link ParameterDriver}. Writes and deletes through this
 * store invalidate its cache.
 */
public final class DefaultParameterStore implements ParameterStore {

    private static final String SERVICE = "parameters";
    private static final Logger LOG = LoggerFactory.getLogger(DefaultParameterStore.class);
    /** One daemon thread polls for every watcher in the JVM; polls are short and infrequent. */
    private static final ScheduledExecutorService WATCHERS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "halyx-parameter-watch");
        thread.setDaemon(true);
        return thread;
    });

    private record Cached<T>(T value, long expiresAtNanos) {
    }

    private final String provider;
    private final ParameterStoreConfig config;
    private final ParameterDriver driver;
    private final InterceptorChain interceptors;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();
    private final ParameterStoreStatistics statistics = new ParameterStoreStatistics();

    public DefaultParameterStore(String provider, ParameterStoreConfig config, ParameterDriver driver,
                                 InterceptorChain interceptors) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public ParameterKind kind() {
        return config.kind();
    }

    @Override
    public Optional<Parameter> get(String name) {
        String full = full(name);
        return cached("get:" + full, () -> execute("get", full, () -> driver.get(full)))
                .map(p -> p.rename(name));
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        String full = full(name);
        Objects.requireNonNull(version, "version");
        capabilities().require(ParameterCapability.VERSIONS, provider);
        return cached("version:" + full + "@" + version, () -> execute("getVersion", full, () -> driver.get(full, version)))
                .map(p -> p.rename(name));
    }

    @Override
    public Map<String, Parameter> getAll(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String full = prefix.isEmpty() ? config.prefix() : full(prefix);
        Map<String, Parameter> found = cached("list:" + full, () -> execute("getAll", full, () -> driver.list(full)));
        Map<String, Parameter> relative = new TreeMap<>();
        found.forEach((name, parameter) -> {
            String rel = relative(name);
            relative.put(rel, parameter.rename(rel));
        });
        return new LinkedHashMap<>(relative);
    }

    @Override
    public Parameter put(String name, String value) {
        String full = full(name);
        Objects.requireNonNull(value, "value");
        Parameter stored = execute("put", full, () -> driver.put(full, value));
        cache.clear();
        return stored.rename(name);
    }

    @Override
    public void delete(String name) {
        String full = full(name);
        execute("delete", full, () -> {
            driver.delete(full);
            return null;
        });
        cache.clear();
    }

    @Override
    public void refresh() {
        cache.clear();
    }

    @Override
    public ParameterStoreStatistics statistics() {
        return statistics;
    }

    @Override
    public AutoCloseable watch(String prefix, Duration interval, Consumer<ParameterChanges> listener) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(listener, "listener");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Watch interval must be positive");
        }
        String full = prefix.isEmpty() ? config.prefix() : full(prefix);
        Map<String, Parameter> baseline = new LinkedHashMap<>(poll(full));
        ScheduledFuture<?> task = WATCHERS.scheduleWithFixedDelay(() -> {
            Map<String, Parameter> current;
            try {
                current = poll(full);
            } catch (RuntimeException e) {
                statistics.watchFailed();
                LOG.warn("Watching {} store '{}' failed; retrying in {}", provider, full, interval, e);
                return;
            }
            ParameterChanges changes = diff(baseline, current);
            baseline.clear();
            baseline.putAll(current);
            if (!changes.isEmpty()) {
                cache.clear();
                remember(changes);
                statistics.changed(changes.added().size() + changes.updated().size() + changes.removed().size());
                try {
                    listener.accept(changes);
                } catch (RuntimeException e) {
                    LOG.warn("Parameter change listener failed", e);
                }
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }

    /**
     * Caches what the watch saw, so reads agree with the change just reported even where the backend's
     * reads lag behind its listing (Secrets Manager).
     */
    private void remember(ParameterChanges changes) {
        if (config.cacheTtl().isZero()) {
            return;
        }
        long expires = System.nanoTime() + config.cacheTtl().toNanos();
        changes.added().forEach((name, p) -> cache.put("get:" + full(name), new Cached<>(Optional.of(p), expires)));
        changes.updated().forEach((name, p) -> cache.put("get:" + full(name), new Cached<>(Optional.of(p), expires)));
        changes.removed().forEach(name -> cache.put("get:" + full(name), new Cached<>(Optional.empty(), expires)));
    }

    /** Current values below {@code full}, relative names, bypassing the cache. */
    private Map<String, Parameter> poll(String full) {
        statistics.backendRead();
        Map<String, Parameter> relative = new LinkedHashMap<>();
        execute("watch", full, () -> driver.list(full)).forEach((name, p) -> relative.put(relative(name), p.rename(relative(name))));
        return relative;
    }

    private static ParameterChanges diff(Map<String, Parameter> before, Map<String, Parameter> after) {
        Map<String, Parameter> added = new LinkedHashMap<>();
        Map<String, Parameter> updated = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>(before.keySet());
        after.forEach((name, p) -> {
            removed.remove(name);
            Parameter old = before.get(name);
            if (old == null) {
                added.put(name, p);
            } else if (!old.version().equals(p.version()) || !old.value().equals(p.value())) {
                updated.put(name, p);
            }
        });
        return new ParameterChanges(added, updated, removed);
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(Objects.requireNonNull(nativeType, "nativeType"));
    }

    private String full(String name) {
        if (config.nativeNames()) {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Parameter name must not be empty");
            }
            return config.prefix() + name;
        }
        return ParameterName.join(config.prefix(), ParameterName.validate(name));
    }

    private String relative(String fullName) {
        if (config.nativeNames() || config.prefix().isEmpty()) {
            return fullName.substring(Math.min(config.prefix().length(), fullName.length()));
        }
        return fullName.substring(config.prefix().length() + 1);
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> load) {
        if (config.cacheTtl().isZero()) {
            statistics.backendRead();
            return load.get();
        }
        long now = System.nanoTime();
        Cached<?> hit = cache.get(key);
        if (hit != null && now - hit.expiresAtNanos() < 0) {
            statistics.cacheHit();
            return (T) hit.value();
        }
        statistics.backendRead();
        T value = load.get();
        cache.put(key, new Cached<>(value, now + config.cacheTtl().toNanos()));
        return value;
    }

    private <T> T execute(String operation, String name, Supplier<T> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider, name, !"put".equals(operation));
        return interceptors.execute(context, () -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }
}
