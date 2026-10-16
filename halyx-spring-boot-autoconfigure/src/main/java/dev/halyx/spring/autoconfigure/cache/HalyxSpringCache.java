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
package dev.halyx.spring.autoconfigure.cache;

import dev.halyx.cache.Cache;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.util.ClassUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Adapts a Halyx {@link Cache} to Spring's cache abstraction. Keys are converted with
 * {@code toString()} (so {@code SimpleKey [a,b]} for multi-argument methods). {@code get(key, loader)},
 * used by {@code @Cacheable(sync = true)}, shares one load per key in this JVM.
 *
 * <p>{@code @Cacheable} methods returning {@code CompletableFuture}, {@code Mono} or {@code Flux} use
 * {@link #retrieve(Object)} and {@link #retrieve(Object, Supplier)}, which run the cache's blocking calls on
 * {@code executor}. Spring writes and evicts their results from within the reactive pipeline, so a
 * {@link #put} or {@link #evict} called on a Reactor non-blocking thread, such as a Netty event loop, is also
 * handed to {@code executor} rather than blocking that thread; anywhere else they complete before returning.
 */
public class HalyxSpringCache extends AbstractValueAdaptingCache {

    private static final boolean REACTOR_PRESENT =
            ClassUtils.isPresent("reactor.core.scheduler.Schedulers", HalyxSpringCache.class.getClassLoader());

    private final String name;
    private final Cache<Object> cache;
    private final Executor executor;

    public HalyxSpringCache(String name, Cache<Object> cache, boolean allowNullValues) {
        this(name, cache, allowNullValues, HalyxSpringCache::virtualThread);
    }

    public HalyxSpringCache(String name, Cache<Object> cache, boolean allowNullValues, Executor executor) {
        super(allowNullValues);
        this.name = name;
        this.cache = cache;
        this.executor = executor;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Cache<Object> getNativeCache() {
        return cache;
    }

    @Override
    protected Object lookup(Object key) {
        return cache.get(key(key)).orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object stored = cache.getOrLoad(key(key), () -> {
            try {
                return toStoreValue(valueLoader.call());
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
        });
        return (T) fromStoreValue(stored);
    }

    /**
     * Completes with the stored value, wrapped when null values are allowed, or with {@code null} on a miss,
     * which this cache can only tell by asking the server.
     */
    @Override
    public CompletableFuture<?> retrieve(Object key) {
        return CompletableFuture.supplyAsync(() -> {
            Object stored = lookup(key);
            if (stored == null) {
                return null;
            }
            return isAllowNullValues() ? toValueWrapper(stored) : fromStoreValue(stored);
        }, executor);
    }

    /** Loads once per key in this JVM, as {@link #get(Object, Callable)} does; the loader's failure fails the result. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return CompletableFuture.supplyAsync(() -> (T) fromStoreValue(cache.getOrLoad(key(key),
                () -> toStoreValue(valueLoader.get().join()))), executor)
                .exceptionallyCompose(e -> CompletableFuture.failedFuture(
                        e instanceof CompletionException && e.getCause() != null ? e.getCause() : e));
    }

    @Override
    public void put(Object key, Object value) {
        String k = key(key);
        Object stored = toStoreValue(value);
        runWithoutBlockingAnEventLoop(() -> cache.put(k, stored));
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        String k = key(key);
        if (cache.putIfAbsent(k, toStoreValue(value))) {
            return null;
        }
        return toValueWrapper(cache.get(k).orElse(null));
    }

    @Override
    public void evict(Object key) {
        String k = key(key);
        runWithoutBlockingAnEventLoop(() -> cache.delete(k));
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return cache.delete(key(key));
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public boolean invalidate() {
        return cache.clear() > 0;
    }

    private void runWithoutBlockingAnEventLoop(Runnable write) {
        if (REACTOR_PRESENT && reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
            executor.execute(write);
        } else {
            write.run();
        }
    }

    private static void virtualThread(Runnable task) {
        Thread.ofVirtual().name("halyx-cache").start(task);
    }

    private static String key(Object key) {
        return key instanceof String s ? s : String.valueOf(key);
    }
}
