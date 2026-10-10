package org.kamranzafar.cirrus.spring.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.cache.CacheMeterBinder;
import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.cache.CacheStatistics;

/**
 * Publishes a Cirrus cache's statistics with Micrometer's standard cache meters
 * ({@code cache.gets{result=hit|miss}}, {@code cache.puts}, {@code cache.evictions}), plus
 * {@code cache.loads{result=success|failure}} and {@code cache.fail.open}.
 */
public class CirrusCacheMeterBinder extends CacheMeterBinder<Cache<?>> {

    public CirrusCacheMeterBinder(Cache<?> cache, String name, Iterable<Tag> tags) {
        super(cache, name, tags);
    }

    private CacheStatistics stats() {
        return getCache() == null ? new CacheStatistics() : getCache().statistics();
    }

    @Override
    protected Long size() {
        return null; // a server-side key count would need a scan
    }

    @Override
    protected long hitCount() {
        return stats().hits();
    }

    @Override
    protected Long missCount() {
        return stats().misses();
    }

    @Override
    protected Long evictionCount() {
        return stats().evictions();
    }

    @Override
    protected long putCount() {
        return stats().puts();
    }

    @Override
    protected void bindImplementationSpecificMetrics(MeterRegistry registry) {
        Cache<?> cache = getCache();
        FunctionCounter.builder("cache.loads", cache, c -> c.statistics().loads())
                .tags(getTagsWithCacheName()).tag("result", "success")
                .description("Values loaded by getOrLoad").register(registry);
        FunctionCounter.builder("cache.loads", cache, c -> c.statistics().loadFailures())
                .tags(getTagsWithCacheName()).tag("result", "failure")
                .description("Loader failures in getOrLoad").register(registry);
        FunctionCounter.builder("cache.fail.open", cache, c -> c.statistics().failOpenSkips())
                .tags(getTagsWithCacheName())
                .description("Operations skipped because the cache server was unavailable").register(registry);
    }
}
