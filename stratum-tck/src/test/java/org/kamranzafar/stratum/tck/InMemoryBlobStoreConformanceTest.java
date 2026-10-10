package org.kamranzafar.stratum.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.storage.BlobStore;

/** Runs the TCK against the in-memory provider, which also checks the TCK itself. */
class InMemoryBlobStoreConformanceTest extends BlobStoreConformanceTest {

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
    protected BlobStore blobStore() {
        return stratum.provider("test").blobStore("tck");
    }
}
