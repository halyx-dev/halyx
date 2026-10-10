package org.kamranzafar.stratum.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.config.ProviderConfig;

import java.net.URI;

/** Runs the cache TCK against the in-memory provider. */
class InMemoryCacheConformanceTest extends CacheConformanceTest {

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder().register("test", ProviderConfig.of("memory")).build();
    }

    @AfterAll
    static void stop() {
        stratum.close();
    }

    @Override
    protected CloudContext context() {
        return stratum.provider("test");
    }

    @Override
    protected CacheConfig.Builder config() {
        return CacheConfig.builder(URI.create("redis://memory:6379"));
    }
}
