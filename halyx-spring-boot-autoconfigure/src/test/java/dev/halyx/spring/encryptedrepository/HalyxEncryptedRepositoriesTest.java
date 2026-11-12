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
package dev.halyx.spring.encryptedrepository;

import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.data.HalyxRepositoriesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repositories with and without {@code @Encrypted} components, over {@code halyx.document-stores} and a key of
 * {@code halyx.keys} on the in-memory provider, on Boot 3.5 and 4.
 */
class HalyxEncryptedRepositoriesTest {

    /** Stands in for the application class: its package is scanned for repositories. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class Application {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxRepositoriesAutoConfiguration.class))
            .withUserConfiguration(Application.class)
            .withPropertyValues(
                    "halyx.providers.local.type=memory",
                    "halyx.document-stores.patients.table=patients",
                    "halyx.document-stores.wards.table=wards",
                    "halyx.key-stores.kms.location=tests")
            .withBean(BeanPostProcessor.class, HalyxEncryptedRepositoriesTest::memoryKeys);

    /** Creates the memory key {@code records} in each key store as it is made. */
    private static BeanPostProcessor memoryKeys() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KeyStore store) {
                    store.unwrap(InMemoryKeys.class).create("records", KeyPurpose.ENCRYPTION);
                }
                return bean;
            }
        };
    }

    @Test
    void encryptedComponentsAreSealedWithTheNamedKey() {
        runner.withPropertyValues("halyx.keys.records.store=kms", "halyx.keys.records.name=records").run(context -> {
            assertThat(context).hasNotFailed();
            PatientRepository patients = context.getBean(PatientRepository.class);
            WardRepository wards = context.getBean(WardRepository.class);
            KeyStore kms = context.getBean("kms", KeyStore.class);

            wards.save(new Ward("east", "East wing", 12));
            assertThat(kms.statistics().wraps()).as("an entity without @Encrypted needs no key").isZero();

            patients.save(new Patient("p-1", "east", "influenza"));

            assertThat(patients.findById("p-1")).hasValue(new Patient("p-1", "east", "influenza"));
            assertThat(wards.findById("east")).hasValue(new Ward("east", "East wing", 12));
            assertThat(kms.statistics().wraps()).isEqualTo(1);
            var stored = context.getBean("patients", DocumentStore.class).get(Key.of("p-1")).orElseThrow().document();
            assertThat(stored.get("diagnosis")).as("sealed in the table").isInstanceOf(byte[].class);
            assertThat(stored.getString("ward")).hasValue("east");
        });
    }

    @Test
    void aMissingKeyBeanFailsAtStartup() {
        runner.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("@Encrypted(\"records\") names no CryptoKey bean"));
    }
}
