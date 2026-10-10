package org.kamranzafar.stratum.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;

class InMemoryParameterStoreConformanceTest extends ParameterStoreConformanceTest {

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
    protected ParameterStoreConfig.Builder config() {
        return ParameterStoreConfig.secrets();
    }
}
