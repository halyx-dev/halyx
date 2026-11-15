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
package dev.halyx.spring.keys;

import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.memory.InMemoryParameters;
import dev.halyx.parameters.EncryptedParameterStore;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parameter stores whose values Halyx seals with a key from properties, alone and with a server-side key. */
class HalyxEncryptedParameterStoresTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory", "halyx.key-stores.kms.location=tests",
                    "halyx.parameter-stores.plain.kind=secrets")
            .withBean(BeanPostProcessor.class, HalyxEncryptedParameterStoresTest::memoryKeys);

    /** Creates the memory keys {@code storage} and {@code sealing} in each key store as it is made. */
    private static BeanPostProcessor memoryKeys() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KeyStore store) {
                    store.unwrap(InMemoryKeys.class).create("storage", KeyPurpose.ENCRYPTION);
                    store.unwrap(InMemoryKeys.class).create("sealing", KeyPurpose.ENCRYPTION);
                }
                return bean;
            }
        };
    }

    @Test
    void anEncryptionKeySealsEveryValue() {
        runner.withPropertyValues("halyx.parameter-stores.secrets.kind=secrets",
                "halyx.parameter-stores.secrets.prefix=orders",
                "halyx.parameter-stores.secrets.encryption-key.name=sealing").run(context -> {
            assertThat(context).hasNotFailed();
            ParameterStore secrets = context.getBean("secrets", ParameterStore.class);
            ParameterStore plain = context.getBean("plain", ParameterStore.class);
            KeyStore kms = context.getBean("kms", KeyStore.class);
            assertThat(secrets).isInstanceOf(EncryptedParameterStore.class);
            assertThat(kms.statistics().wraps()).as("no key service call at startup").isZero();
            assertThat(context.getBeanFactory().getBeanDefinition("secrets").getDescription())
                    .endsWith("sealed with sealing");

            secrets.put("db/password", "s3cr3t");

            String stored = plain.require("orders/db/password");
            assertThat(stored).startsWith("hlxs1:");
            assertThat(SealedData.parseText(stored).keys().getFirst().keyId())
                    .isEqualTo(kms.key("sealing").encrypt(new byte[0], new byte[0]).keys().getFirst().keyId());
            assertThat(secrets.require("db/password")).isEqualTo("s3cr3t");
            assertThat(secrets.async().require("db/password").join()).isEqualTo("s3cr3t");
        });
    }

    @Test
    void plaintextIsReadOnlyWhenAllowed() {
        runner.withPropertyValues("halyx.parameter-stores.strict.kind=secrets",
                "halyx.parameter-stores.strict.encryption-key.name=sealing",
                "halyx.parameter-stores.migrating.kind=secrets",
                "halyx.parameter-stores.migrating.encryption-key.name=sealing",
                "halyx.parameter-stores.migrating.allow-plaintext=true").run(context -> {
            context.getBean("plain", ParameterStore.class).put("legacy", "in the clear");
            ParameterStore strict = context.getBean("strict", ParameterStore.class);
            ParameterStore migrating = context.getBean("migrating", ParameterStore.class);

            assertThatThrownBy(() -> strict.require("legacy")).hasMessageContaining("isn't encrypted");
            assertThat(migrating.require("legacy")).isEqualTo("in the clear");
            assertThat(context.getBeanFactory().getBeanDefinition("migrating").getDescription())
                    .endsWith("sealed with sealing, reading plaintext");
        });
    }

    @Test
    void bothKeysSealAndEncryptAtRest() {
        runner.withPropertyValues("halyx.parameter-stores.secrets.kind=secrets",
                "halyx.parameter-stores.secrets.server-side-key.name=storage",
                "halyx.parameter-stores.secrets.encryption-key.name=sealing").run(context -> {
            ParameterStore secrets = context.getBean("secrets", ParameterStore.class);
            ParameterStore plain = context.getBean("plain", ParameterStore.class);
            KeyStore kms = context.getBean("kms", KeyStore.class);
            assertThat(context.getBeanFactory().getBeanDefinition("secrets").getDescription())
                    .endsWith("encrypted at rest with storage, sealed with sealing");

            secrets.put("db/password", "s3cr3t");

            assertThat(plain.unwrap(InMemoryParameters.class).serverSideKey("db/password"))
                    .contains(kms.key("storage").metadata().nativeId());
            assertThat(plain.require("db/password")).startsWith("hlxs1:");
            assertThat(secrets.require("db/password")).isEqualTo("s3cr3t");
        });
    }

    @Test
    void misconfigurationFailsTheStart() {
        runner.withPropertyValues("halyx.parameter-stores.secrets.allow-plaintext=true").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.parameter-stores.secrets.allow-plaintext needs halyx.parameter-stores.secrets.encryption-key"));
        runner.withPropertyValues("halyx.parameter-stores.secrets.encryption-key.store=kms").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.parameter-stores.secrets.encryption-key.name is required"));
        runner.withPropertyValues("halyx.parameter-stores.secrets.encryption-key.name=sealing",
                "halyx.parameter-stores.secrets.encryption-key.store=nope").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.parameter-stores.secrets.encryption-key.store must name one of halyx.key-stores"));
    }
}
