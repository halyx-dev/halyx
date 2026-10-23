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
package dev.halyx.spring.cloudlogins;

import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with a real cloud's logins (tier 5): a provider whose {@code credentials} assume an AWS role with an
 * external ID, impersonate a Google service account, or name an Entra ID service principal with a secret or a
 * federated token, and a {@code BlobStore} bean that uses them, on Boot 3.5 and 4. Runs only with
 * {@code -Dhalyx.real.spring.logins=true}, from the real-cloud scripts' tier 5, which pass the provider type and
 * properties, the credentials ({@code halyx.real.spring.credentials}, comma-separated {@code key=value} pairs that
 * may name environment variables as {@code ${VAR}}) and the bucket ({@code halyx.real.spring.bucket}).
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.logins", matches = "true")
class RealSpringLoginIT {

    private static void addAll(List<String> properties, String prefix, String pairs) {
        for (String pair : pairs.split(",")) {
            if (!pair.isBlank()) {
                properties.add(prefix + pair.trim());
            }
        }
    }

    @Test
    void aBlobStoreBeanUsesTheProvidersCredentials() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.blob-stores.files.bucket=" + System.getProperty("halyx.real.spring.bucket")));
        addAll(properties, "halyx.providers.cloud.properties.", System.getProperty("halyx.real.spring.provider-properties", ""));
        addAll(properties, "halyx.providers.cloud.credentials.", System.getProperty("halyx.real.spring.credentials", ""));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BlobStore files = context.getBean("files", BlobStore.class);
                    BlobKey key = BlobKey.of("logins/spring-" + UUID.randomUUID() + ".txt");

                    files.put(key, BlobContent.ofString("spring"));

                    assertThat(files.getBytes(key)).asString().isEqualTo("spring");
                    files.delete(key);
                });
    }
}
