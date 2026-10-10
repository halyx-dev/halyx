package org.kamranzafar.cirrus.spring.autoconfigure.cache;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.Codec;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Spring {@link CacheManager} over Cirrus caches on one Redis-compatible server. Caches are created
 * on first use, or only from {@code cache-names} when that list is set.
 */
public class CirrusCacheManager implements CacheManager {

    private final Cirrus cirrus;
    private final String provider;
    private final CirrusProperties.CacheManagerProperties properties;
    private final Codec<Object> codec;
    private final Map<String, CirrusSpringCache> caches = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, org.kamranzafar.cirrus.cache.Cache<?>>> listeners = new CopyOnWriteArrayList<>();

    public CirrusCacheManager(Cirrus cirrus, String provider, CirrusProperties.CacheManagerProperties properties,
                               Codec<Object> codec) {
        this.cirrus = cirrus;
        this.provider = provider;
        this.properties = properties;
        this.codec = codec;
        properties.cacheNames().forEach(this::getCache);
    }

    @Override
    public Cache getCache(String name) {
        CirrusSpringCache existing = caches.get(name);
        if (existing != null) {
            return existing;
        }
        if (!properties.cacheNames().isEmpty() && !properties.cacheNames().contains(name)) {
            return null;
        }
        return caches.computeIfAbsent(name, this::create);
    }

    private CirrusSpringCache create(String name) {
        var cache = cirrus.provider(provider).cache(properties.toCacheConfig(name), codec);
        listeners.forEach(listener -> listener.accept(name, cache));
        return new CirrusSpringCache(name, cache, properties.allowNullValues());
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.copyOf(caches.keySet());
    }

    /** Called for every cache, including those created before registration (used for metrics). */
    public void onCacheCreated(BiConsumer<String, org.kamranzafar.cirrus.cache.Cache<?>> listener) {
        listeners.add(listener);
        caches.forEach((name, cache) -> listener.accept(name, cache.getNativeCache()));
    }
}
