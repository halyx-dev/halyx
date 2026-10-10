package org.kamranzafar.stratum.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.tck.ParameterStoreConformanceTest;

/**
 * The parameter store TCK on real GCP, using Application Default Credentials. Skipped unless
 * {@code STRATUM_GCP_PROJECT} is set; {@code STRATUM_GCP_LOCATION} picks the Parameter Manager
 * location (default {@code global}). The identity needs Secret Manager Admin and Parameter Manager
 * Admin on the project. Every test cleans up the names it creates (random {@code tck-*} prefixes).
 */
@EnabledIfEnvironmentVariable(named = "STRATUM_GCP_PROJECT", matches = ".+")
class GcpParameterStoreIT {

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder()
                .register("gcp", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, System.getenv("STRATUM_GCP_PROJECT")))
                .build();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
        }
    }

    private static String location() {
        String location = System.getenv("STRATUM_GCP_LOCATION");
        return location == null || location.isBlank() ? "global" : location;
    }

    @Nested
    class SecretManager extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("gcp");
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
            return stratum.provider("gcp");
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
