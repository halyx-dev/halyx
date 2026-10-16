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

import dev.halyx.Halyx;
import dev.halyx.cache.Codec;
import dev.halyx.spring.autoconfigure.HalyxProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Spring {@link CacheManager} over Halyx caches on one Redis-compatible server. Caches are created
 * on first use, or only from {@code cache-names} when that list is set.
 */
public class HalyxCacheManager implements CacheManager {

    private final Halyx halyx;
    private final String provider;
    private final HalyxProperties.CacheManagerProperties properties;
    private final Codec<Object> codec;
    private final Executor executor;
    private final Map<String, HalyxSpringCache> caches = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, dev.halyx.cache.Cache<?>>> listeners = new CopyOnWriteArrayList<>();

    public HalyxCacheManager(Halyx halyx, String provider, HalyxProperties.CacheManagerProperties properties,
                               Codec<Object> codec) {
        this(halyx, provider, properties, codec, task -> Thread.ofVirtual().name("halyx-cache").start(task));
    }

    /**
     * @param executor runs the caches' blocking calls for {@code CompletableFuture} and reactive
     *                 {@code @Cacheable} methods; see {@link HalyxSpringCache}
     */
    public HalyxCacheManager(Halyx halyx, String provider, HalyxProperties.CacheManagerProperties properties,
                               Codec<Object> codec, Executor executor) {
        this.halyx = halyx;
        this.provider = provider;
        this.properties = properties;
        this.codec = codec;
        this.executor = executor;
        properties.cacheNames().forEach(this::getCache);
    }

    @Override
    public Cache getCache(String name) {
        HalyxSpringCache existing = caches.get(name);
        if (existing != null) {
            return existing;
        }
        if (!properties.cacheNames().isEmpty() && !properties.cacheNames().contains(name)) {
            return null;
        }
        return caches.computeIfAbsent(name, this::create);
    }

    private HalyxSpringCache create(String name) {
        var cache = halyx.provider(provider).cache(properties.toCacheConfig(name), codec);
        listeners.forEach(listener -> listener.accept(name, cache));
        return new HalyxSpringCache(name, cache, properties.allowNullValues(), executor);
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.copyOf(caches.keySet());
    }

    /** Called for every cache, including those created before registration (used for metrics). */
    public void onCacheCreated(BiConsumer<String, dev.halyx.cache.Cache<?>> listener) {
        listeners.add(listener);
        caches.forEach((name, cache) -> listener.accept(name, cache.getNativeCache()));
    }
}
