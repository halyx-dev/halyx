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
package org.kamranzafar.halyx.spring.autoconfigure.cache;

import org.kamranzafar.halyx.cache.Cache;
import org.springframework.cache.support.AbstractValueAdaptingCache;

import java.util.concurrent.Callable;

/**
 * Adapts a Halyx {@link Cache} to Spring's cache abstraction. Keys are converted with
 * {@code toString()} (so {@code SimpleKey [a,b]} for multi-argument methods). {@code get(key, loader)},
 * used by {@code @Cacheable(sync = true)}, shares one load per key in this JVM.
 */
public class HalyxSpringCache extends AbstractValueAdaptingCache {

    private final String name;
    private final Cache<Object> cache;

    public HalyxSpringCache(String name, Cache<Object> cache, boolean allowNullValues) {
        super(allowNullValues);
        this.name = name;
        this.cache = cache;
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

    @Override
    public void put(Object key, Object value) {
        cache.put(key(key), toStoreValue(value));
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
        cache.delete(key(key));
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

    private static String key(Object key) {
        return key instanceof String s ? s : String.valueOf(key);
    }
}
