package org.kamranzafar.stratum.spring.autoconfigure.cache;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.Codec;
import org.kamranzafar.stratum.spring.autoconfigure.StratumProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Spring {@link CacheManager} over Stratum caches on one Redis-compatible server. Caches are created
 * on first use, or only from {@code cache-names} when that list is set.
 */
public class StratumCacheManager implements CacheManager {

    private final Stratum stratum;
    private final String provider;
    private final StratumProperties.CacheManagerProperties properties;
    private final Codec<Object> codec;
    private final Map<String, StratumSpringCache> caches = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, org.kamranzafar.stratum.cache.Cache<?>>> listeners = new CopyOnWriteArrayList<>();

    public StratumCacheManager(Stratum stratum, String provider, StratumProperties.CacheManagerProperties properties,
                               Codec<Object> codec) {
        this.stratum = stratum;
        this.provider = provider;
        this.properties = properties;
        this.codec = codec;
        properties.cacheNames().forEach(this::getCache);
    }

    @Override
    public Cache getCache(String name) {
        StratumSpringCache existing = caches.get(name);
        if (existing != null) {
            return existing;
        }
        if (!properties.cacheNames().isEmpty() && !properties.cacheNames().contains(name)) {
            return null;
        }
        return caches.computeIfAbsent(name, this::create);
    }

    private StratumSpringCache create(String name) {
        var cache = stratum.provider(provider).cache(properties.toCacheConfig(name), codec);
        listeners.forEach(listener -> listener.accept(name, cache));
        return new StratumSpringCache(name, cache, properties.allowNullValues());
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.copyOf(caches.keySet());
    }

    /** Called for every cache, including those created before registration (used for metrics). */
    public void onCacheCreated(BiConsumer<String, org.kamranzafar.stratum.cache.Cache<?>> listener) {
        listeners.add(listener);
        caches.forEach((name, cache) -> listener.accept(name, cache.getNativeCache()));
    }
}
