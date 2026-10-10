package org.kamranzafar.cirrus.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.config.ProviderConfig;

import java.net.URI;

/** Runs the cache TCK against the in-memory provider. */
class InMemoryCacheConformanceTest extends CacheConformanceTest {

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("test", ProviderConfig.of("memory")).build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
    }

    @Override
    protected CloudContext context() {
        return cirrus.provider("test");
    }

    @Override
    protected CacheConfig.Builder config() {
        return CacheConfig.builder(URI.create("redis://memory:6379"));
    }
}
