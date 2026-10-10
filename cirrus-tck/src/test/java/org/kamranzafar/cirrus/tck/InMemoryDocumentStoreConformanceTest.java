package org.kamranzafar.cirrus.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.TableConfig;

/** Runs the document store TCK against the in-memory provider, which also checks the TCK itself. */
class InMemoryDocumentStoreConformanceTest extends DocumentStoreConformanceTest {

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
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return cirrus.provider("test").documentStore(table.build());
    }
}
