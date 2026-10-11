/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.cirrus.gcp;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The parameter store TCK on Secret Manager emulated by Floci-gcp (gRPC). */
@Testcontainers(disabledWithoutDocker = true)
class SecretManagerConformanceIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(4588)
            .waitingFor(Wait.forHttp("/health").forPort(4588));

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4588)))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "floci-local"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    @Nested
    class Secrets extends ParameterStoreConformanceTest {
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

    @Test
    void secretIdsUseDoubleUnderscoreSeparators() {
        ParameterStore store = cirrus.provider("gcp").parameterStore(ParameterStoreConfig.secrets().build());
        store.put("orders/db/password", "pw");

        var client = store.unwrap(SecretManagerServiceClient.class);
        assertThat(client.accessSecretVersion(SecretVersionName.of("floci-local", "orders__db__password", "latest"))
                .getPayload().getData().toStringUtf8()).isEqualTo("pw");
        store.delete("orders/db/password");
    }
}
