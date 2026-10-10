package org.kamranzafar.cirrus.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.tck.ParameterStoreConformanceTest;

/**
 * The parameter store TCK on real GCP, using Application Default Credentials. Skipped unless
 * {@code CIRRUS_GCP_PROJECT} is set; {@code CIRRUS_GCP_LOCATION} picks the Parameter Manager
 * location (default {@code global}). The identity needs Secret Manager Admin and Parameter Manager
 * Admin on the project. Every test cleans up the names it creates (random {@code tck-*} prefixes).
 */
@EnabledIfEnvironmentVariable(named = "CIRRUS_GCP_PROJECT", matches = ".+")
class GcpParameterStoreIT {

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, System.getenv("CIRRUS_GCP_PROJECT")))
                .build();
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    private static String location() {
        String location = System.getenv("CIRRUS_GCP_LOCATION");
        return location == null || location.isBlank() ? "global" : location;
    }

    @Nested
    class SecretManager extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Nested
    class ParameterManager extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location(location());
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }
}
