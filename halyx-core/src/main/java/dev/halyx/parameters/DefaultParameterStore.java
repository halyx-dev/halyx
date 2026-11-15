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
package dev.halyx.parameters;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.spi.ServerSideKey;
import dev.halyx.parameters.spi.ParameterDriver;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The portable {@link ParameterStore}: name validation, prefixing, a read cache, interceptors and
 * exception translation around a provider {@link ParameterDriver}. Writes and deletes through this
 * store invalidate its cache. Its encrypted views ({@link #encryptedWith}) are stores of their own over the same driver,
 * whose cache holds opened values.
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

    private static final String PING_NAME = "halyx-ping";

    private record Cached<T>(T value, long expiresAtNanos) {
    }

    private final String provider;
    private final ParameterStoreConfig config;
    private final ParameterDriver driver;
    private final InterceptorChain interceptors;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();
    private final ParameterStoreStatistics statistics = new ParameterStoreStatistics();
    private final Async async = new Async(InterceptorChain.blocking());
    /** How values are sealed and opened, for an encrypted view; null for a plain store. */
    private final ParameterSealing sealing;

    public DefaultParameterStore(String provider, ParameterStoreConfig config, ParameterDriver driver,
                                 InterceptorChain interceptors) {
        this(provider, config, driver, interceptors, null);
    }

    private DefaultParameterStore(String provider, ParameterStoreConfig config, ParameterDriver driver,
                                  InterceptorChain interceptors, ParameterSealing sealing) {
        this.sealing = sealing;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        config.serverSideKey().ifPresent(this::checkServerSideKey);
    }

    /** Checks what needs no key service call: the capability, and that the key is this provider's. */
    private void checkServerSideKey(CryptoKey key) {
        driver.capabilities().require(ParameterCapability.SERVER_SIDE_KMS, provider);
        if (!key.provider().equals(provider)) {
            throw new InvalidArgumentException("Server-side key '" + key.name() + "' is a " + key.provider()
                    + " key; a " + provider + " store can only encrypt with " + provider + " keys", null, provider, null);
        }
    }

    /**
     * The driver for a write: keyed with the store's server-side key, else the plain driver. The key's metadata is
     * cached, so this costs a key service call only on first use and refresh.
     */
    private ParameterDriver writer() {
        return config.serverSideKey().map(key -> keyed(key, key.metadata())).orElse(driver);
    }

    /** {@link #writer()} for an asynchronous write: the key's metadata is read without blocking where it can be. */
    private CompletableFuture<ParameterDriver> writerAsync(Executor blocking) {
        Optional<CryptoKey> configured = config.serverSideKey();
        if (configured.isEmpty()) {
            return CompletableFuture.completedFuture(driver);
        }
        CryptoKey key = configured.get();
        return key.async(blocking).metadata().thenApply(metadata -> keyed(key, metadata));
    }

    private ParameterDriver keyed(CryptoKey key, KeyMetadata metadata) {
        if (!metadata.purposes().contains(KeyPurpose.ENCRYPTION)) {
            throw new InvalidArgumentException("Server-side key '" + key.name() + "' can't encrypt: its purposes are "
                    + metadata.purposes(), null, provider, null);
        }
        return driver.withServerSideKey(new ServerSideKey(metadata.nativeId(), key.options().properties()));
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
        return cached("get:" + full, () -> open(full, execute("get", full, () -> driver.get(full))))
                .map(p -> p.rename(name));
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        String full = full(name);
        Objects.requireNonNull(version, "version");
        capabilities().require(ParameterCapability.VERSIONS, provider);
        return cached("version:" + full + "@" + version,
                () -> open(full, execute("getVersion", full, () -> driver.get(full, version))))
                .map(p -> p.rename(name));
    }

    @Override
    public Map<String, Parameter> getAll(String prefix) {
        String full = fullPrefix(prefix);
        return relativeAll(cached("list:" + full, () -> openAll(execute("getAll", full, () -> driver.list(full)))));
    }

    /** A listing by relative names, sorted. */
    private Map<String, Parameter> relativeAll(Map<String, Parameter> found) {
        Map<String, Parameter> relative = new TreeMap<>();
        found.forEach((name, parameter) -> {
            String rel = relative(name);
            relative.put(rel, parameter.rename(rel));
        });
        return new LinkedHashMap<>(relative);
    }

    /** The full name of a listing's prefix: the store's own prefix for "". */
    private String fullPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix.isEmpty() ? config.prefix() : full(prefix);
    }

    @Override
    public Parameter put(String name, String value) {
        String full = full(name);
        Objects.requireNonNull(value, "value");
        String sent = sealing == null ? value : sealing.seal(name, full, value);
        Parameter stored = execute("put", full, () -> writer().put(full, sent));
        cache.clear();
        return written(name, value, stored);
    }

    /** What a put returns: the stored version, with the value as given rather than as sealed. */
    private Parameter written(String name, String value, Parameter stored) {
        return sealing == null ? stored.rename(name) : new Parameter(name, value, stored.version(), stored.lastModified());
    }

    @Override
    public EncryptedParameterStore encryptedWith(CryptoKey key) {
        Objects.requireNonNull(key, "key");
        return encrypted(name -> key, key.store());
    }

    @Override
    public EncryptedParameterStore encryptedWith(KeyStore keys, Function<String, String> keyName) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(keyName, "keyName");
        return encrypted(name -> keys.key(keyName.apply(name)), keys);
    }

    private EncryptedParameterStore encrypted(Function<String, CryptoKey> keys, KeyStore opener) {
        if (sealing != null) {
            throw new UnsupportedFeatureException("This store is already an encrypted view", null, provider, null);
        }
        return new EncryptedView(new DefaultParameterStore(provider, config, driver, interceptors,
                new ParameterSealing(keys, opener, false, provider, config.kind(), driver.maxValueBytes())));
    }

    private Optional<Parameter> open(String full, Optional<Parameter> found) {
        return sealing == null ? found : sealing.open(full, found);
    }

    /** A listing, by full names, opened. */
    private Map<String, Parameter> openAll(Map<String, Parameter> found) {
        return sealing == null || found.isEmpty() ? found : sealing.openAll(found, InterceptorChain.blocking());
    }

    /** What the watch found, by relative names, opened. */
    private ParameterChanges open(ParameterChanges changes) {
        if (sealing == null) {
            return changes;
        }
        return new ParameterChanges(openRelative(changes.added()), openRelative(changes.updated()), changes.removed());
    }

    private Map<String, Parameter> openRelative(Map<String, Parameter> byRelative) {
        Map<String, Parameter> byFull = new LinkedHashMap<>();
        byRelative.forEach((name, p) -> byFull.put(fullOf(name), p));
        Map<String, Parameter> opened = openAll(byFull);
        Map<String, Parameter> out = new LinkedHashMap<>();
        byRelative.keySet().forEach(name -> out.put(name, opened.get(fullOf(name))));
        return out;
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
    public AsyncParameterStore async() {
        return async;
    }

    @Override
    public AsyncParameterStore async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    @Override
    public void refresh() {
        cache.clear();
    }

    @Override
    public void ping() {
        String full = full(PING_NAME);
        execute("ping", full, () -> driver.get(full));
    }

    @Override
    public ParameterStoreStatistics statistics() {
        return statistics;
    }

    @Override
    public AutoCloseable watch(String prefix, Duration interval, Consumer<ParameterChanges> listener) {
        Objects.requireNonNull(listener, "listener");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Watch interval must be positive");
        }
        String full = fullPrefix(prefix);
        Map<String, Parameter> reported = new LinkedHashMap<>(poll(full));
        ScheduledFuture<?> task = WATCHERS.scheduleWithFixedDelay(() -> {
            ParameterChanges changes;
            try {
                Map<String, Parameter> next = new LinkedHashMap<>(reported);
                changes = open(confirmedChanges(next, poll(full)));
                // only once every change opened, so a failure reports the same changes at the next poll
                reported.clear();
                reported.putAll(next);
            } catch (RuntimeException e) {
                statistics.watchFailed();
                LOG.warn("Watching {} store '{}' failed; retrying in {}", provider, full, interval, e);
                return;
            }
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
     * The changes since what the watch last reported, applied to {@code reported}. A listing may come from a
     * replica that lags the writes (SSM's GetParametersByPath goes back and forth for a second or two), so
     * each name it disagrees on is read directly before it counts: a stale listing reports nothing, and a
     * change shows once the read agrees. A read older than what was reported is ignored.
     */
    private ParameterChanges confirmedChanges(Map<String, Parameter> reported, Map<String, Parameter> listed) {
        Set<String> disputed = new LinkedHashSet<>();
        listed.forEach((name, p) -> {
            Parameter old = reported.get(name);
            if (old == null || differs(old, p)) {
                disputed.add(name);
            }
        });
        reported.keySet().stream().filter(name -> !listed.containsKey(name)).forEach(disputed::add);

        Map<String, Parameter> added = new LinkedHashMap<>();
        Map<String, Parameter> updated = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();
        for (String name : disputed) {
            String fullName = fullOf(name);
            statistics.backendRead();
            Optional<Parameter> current = execute("watch", fullName, () -> driver.get(fullName)).map(p -> p.rename(name));
            Parameter old = reported.get(name);
            if (current.isEmpty()) {
                if (old != null) {
                    removed.add(name);
                    reported.remove(name);
                }
            } else if (old == null) {
                added.put(name, current.get());
                reported.put(name, current.get());
            } else if (differs(old, current.get()) && !olderThan(current.get(), old)) {
                updated.put(name, current.get());
                reported.put(name, current.get());
            }
        }
        return new ParameterChanges(added, updated, removed);
    }

    private static boolean differs(Parameter a, Parameter b) {
        return !a.version().equals(b.version()) || !a.value().equals(b.value());
    }

    private static boolean olderThan(Parameter p, Parameter than) {
        return p.lastModified() != null && than.lastModified() != null && p.lastModified().isBefore(than.lastModified());
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

    /** The backend name of a relative name taken from a listing; the inverse of {@link #relative}. */
    private String fullOf(String relativeName) {
        if (config.nativeNames() || config.prefix().isEmpty()) {
            return config.prefix() + relativeName;
        }
        return config.prefix() + "/" + relativeName;
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

    /** The asynchronous {@link #cached}: a fresh hit completes at once, without the backend. */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> cachedAsync(String key, Supplier<CompletableFuture<T>> load) {
        if (config.cacheTtl().isZero()) {
            statistics.backendRead();
            return load.get();
        }
        long now = System.nanoTime();
        Cached<?> hit = cache.get(key);
        if (hit != null && now - hit.expiresAtNanos() < 0) {
            statistics.cacheHit();
            return CompletableFuture.completedFuture((T) hit.value());
        }
        statistics.backendRead();
        return load.get().thenApply(value -> {
            cache.put(key, new Cached<>(value, now + config.cacheTtl().toNanos()));
            return value;
        });
    }

    /** The asynchronous {@link #execute}: a failure, thrown or returned, comes back translated in the future. */
    private <T> CompletableFuture<T> executeAsync(String operation, String name, Executor blocking,
                                                  Function<Executor, CompletableFuture<T>> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider, name, !"put".equals(operation));
        return interceptors.executeAsync(context, () -> {
            CompletableFuture<T> future;
            try {
                future = call.apply(blocking);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(driver.translate(e));
            }
            return future.exceptionallyCompose(error -> {
                Throwable cause = AsyncInvocation.unwrap(error);
                return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
            });
        }, blocking);
    }

    /** A failed future for an argument check that throws, so async callers see one kind of failure. */
    private static <K, T> CompletableFuture<T> checked(Supplier<K> check, Function<K, CompletableFuture<T>> call) {
        K checked;
        try {
            checked = check.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return call.apply(checked);
    }

    /** The asynchronous view: the same cache, statistics and interceptors over the driver's asynchronous calls. */
    private final class Async implements AsyncParameterStore {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
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
        public CompletableFuture<Optional<Parameter>> get(String name) {
            return checked(() -> full(name), full -> cachedAsync("get:" + full,
                    () -> openAsync(full, executeAsync("get", full, blocking, executor -> driver.getAsync(full, executor))))
                    .thenApply(found -> found.map(p -> p.rename(name))));
        }

        @Override
        public CompletableFuture<Optional<Parameter>> get(String name, String version) {
            return checked(() -> {
                String full = full(name);
                Objects.requireNonNull(version, "version");
                capabilities().require(ParameterCapability.VERSIONS, provider);
                return full;
            }, full -> cachedAsync("version:" + full + "@" + version,
                    () -> openAsync(full, executeAsync("getVersion", full, blocking,
                            executor -> driver.getAsync(full, version, executor))))
                    .thenApply(found -> found.map(p -> p.rename(name))));
        }

        @Override
        public CompletableFuture<Map<String, Parameter>> getAll(String prefix) {
            return checked(() -> fullPrefix(prefix), full -> cachedAsync("list:" + full,
                    () -> executeAsync("getAll", full, blocking, executor -> driver.listAsync(full, executor))
                            .thenCompose(found -> sealing == null || found.isEmpty()
                                    ? CompletableFuture.completedFuture(found)
                                    : sealing.openAllAsync(found, blocking)))
                    .thenApply(DefaultParameterStore.this::relativeAll));
        }

        @Override
        public CompletableFuture<Parameter> put(String name, String value) {
            return checked(() -> {
                String full = full(name);
                Objects.requireNonNull(value, "value");
                return full;
            }, full -> sealAsync(name, full, value).thenCompose(sealed -> executeAsync("put", full, blocking,
                            executor -> writerAsync(executor).thenCompose(writer -> writer.putAsync(full, sealed, executor))))
                    .thenApply(stored -> {
                        cache.clear();
                        return written(name, value, stored);
                    }));
        }

        private CompletableFuture<Optional<Parameter>> openAsync(String full, CompletableFuture<Optional<Parameter>> found) {
            return sealing == null ? found : found.thenCompose(p -> sealing.openAsync(full, p, blocking));
        }

        private CompletableFuture<String> sealAsync(String name, String full, String value) {
            if (sealing == null) {
                return CompletableFuture.completedFuture(value);
            }
            try {
                return sealing.sealAsync(name, full, value, blocking);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<Void> delete(String name) {
            return checked(() -> full(name), full -> executeAsync("delete", full, blocking,
                    executor -> driver.deleteAsync(full, executor)).thenRun(cache::clear));
        }

        @Override
        public CompletableFuture<Void> ping() {
            String full = full(PING_NAME);
            return executeAsync("ping", full, blocking, executor -> driver.getAsync(full, executor)).thenApply(found -> null);
        }

        @Override
        public ParameterStore blocking() {
            return DefaultParameterStore.this;
        }
    }

    /**
     * The encrypted view {@link #encryptedWith} returns: a store of its own over the same driver, sealing and opening
     * values, with its own cache of opened values and its own statistics.
     */
    private static final class EncryptedView implements EncryptedParameterStore {

        private final DefaultParameterStore store;

        EncryptedView(DefaultParameterStore store) {
            this.store = store;
        }

        @Override
        public EncryptedParameterStore allowPlaintext() {
            return store.sealing.allowsPlaintext() ? this : new EncryptedView(new DefaultParameterStore(store.provider,
                    store.config, store.driver, store.interceptors, store.sealing.allowingPlaintext()));
        }

        @Override
        public String provider() {
            return store.provider();
        }

        @Override
        public ParameterKind kind() {
            return store.kind();
        }

        @Override
        public Optional<Parameter> get(String name) {
            return store.get(name);
        }

        @Override
        public Optional<Parameter> get(String name, String version) {
            return store.get(name, version);
        }

        @Override
        public Map<String, Parameter> getAll(String prefix) {
            return store.getAll(prefix);
        }

        @Override
        public Parameter put(String name, String value) {
            return store.put(name, value);
        }

        @Override
        public void delete(String name) {
            store.delete(name);
        }

        @Override
        public void refresh() {
            store.refresh();
        }

        @Override
        public AutoCloseable watch(String prefix, Duration interval, Consumer<ParameterChanges> listener) {
            return store.watch(prefix, interval, listener);
        }

        @Override
        public void ping() {
            store.ping();
        }

        @Override
        public AsyncParameterStore async() {
            return new AsyncView(store.async(), this);
        }

        @Override
        public AsyncParameterStore async(Executor blocking) {
            return new AsyncView(store.async(blocking), this);
        }

        @Override
        public EncryptedParameterStore encryptedWith(CryptoKey key) {
            return store.encryptedWith(key);
        }

        @Override
        public EncryptedParameterStore encryptedWith(KeyStore keys, Function<String, String> keyName) {
            return store.encryptedWith(keys, keyName);
        }

        @Override
        public ParameterStoreStatistics statistics() {
            return store.statistics();
        }

        @Override
        public Capabilities capabilities() {
            return store.capabilities();
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            return store.unwrap(nativeType);
        }

        @Override
        public String toString() {
            return "EncryptedParameterStore[" + store.provider + ", " + store.config + "]";
        }
    }

    /** An encrypted view's asynchronous view, whose {@link #blocking()} is the encrypted view itself. */
    private record AsyncView(AsyncParameterStore async, EncryptedParameterStore view) implements AsyncParameterStore {

        @Override
        public String provider() {
            return async.provider();
        }

        @Override
        public ParameterKind kind() {
            return async.kind();
        }

        @Override
        public CompletableFuture<Optional<Parameter>> get(String name) {
            return async.get(name);
        }

        @Override
        public CompletableFuture<Optional<Parameter>> get(String name, String version) {
            return async.get(name, version);
        }

        @Override
        public CompletableFuture<Map<String, Parameter>> getAll(String prefix) {
            return async.getAll(prefix);
        }

        @Override
        public CompletableFuture<Parameter> put(String name, String value) {
            return async.put(name, value);
        }

        @Override
        public CompletableFuture<Void> delete(String name) {
            return async.delete(name);
        }

        @Override
        public CompletableFuture<Void> ping() {
            return async.ping();
        }

        @Override
        public ParameterStore blocking() {
            return view;
        }
    }
}
