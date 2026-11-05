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

import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.reactor.ReactiveCryptoKey;
import dev.halyx.reactor.ReactiveKeyStore;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Key stores and keys from properties, on the in-memory provider, on Boot 3.5 and 4. */
class HalyxKeysTest {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory");

    /** Creates memory keys in each key store as it is made, before any key bean reads them. */
    private static BeanPostProcessor memoryKeys(Map<String, KeyPurpose> keys) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KeyStore store) {
                    InMemoryKeys memory = store.unwrap(InMemoryKeys.class);
                    keys.forEach((key, purpose) -> {
                        if (purpose == KeyPurpose.SIGNING) {
                            memory.create(key, purpose, SigningAlgorithm.ECDSA_P256_SHA256);
                        } else {
                            memory.create(key, purpose);
                        }
                    });
                }
                return bean;
            }
        };
    }

    @Test
    void keyStoresAndKeysFromProperties() {
        runner.withPropertyValues(
                        "halyx.key-stores.kms.prefix=orders-",
                        "halyx.key-stores.kms.segment-size=16KB",
                        "halyx.key-stores.kms.data-key-cache.ttl=5m",
                        "halyx.key-stores.kms.data-key-cache.max-entries=100",
                        "halyx.keys.data.store=kms",
                        "halyx.keys.tokens.name=signing",
                        "halyx.keys.tokens.signing-algorithm=ecdsa-p256-sha256")
                .withBean(BeanPostProcessor.class,
                        () -> memoryKeys(Map.of("orders-data", KeyPurpose.ENCRYPTION, "orders-signing", KeyPurpose.SIGNING)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KeyStore kms = context.getBean("kms", KeyStore.class);
                    CryptoKey data = context.getBean("data", CryptoKey.class);
                    CryptoKey tokens = context.getBean("tokens", CryptoKey.class);

                    assertThat(data.store()).isSameAs(kms);
                    assertThat(data.name()).isEqualTo("data");
                    assertThat(tokens.name()).isEqualTo("signing");
                    SealedData sealed = data.encrypt(DATA, AAD);
                    assertThat(kms.decrypt(sealed, AAD)).isEqualTo(DATA);
                    assertThat(sealed.toBytes().length).isGreaterThan(DATA.length);
                    Signature signature = tokens.sign(DATA);
                    assertThat(signature.algorithm()).isEqualTo(SigningAlgorithm.ECDSA_P256_SHA256);
                    assertThat(tokens.verify(DATA, signature)).isTrue();
                    // The data key cache from the properties: a second decrypt makes no unwrap call
                    kms.decrypt(sealed, AAD);
                    assertThat(kms.statistics().unwrapCacheHits()).isEqualTo(1);
                    // Created after the store it uses, and so destroyed before it
                    assertThat(context.getBeanFactory().getDependenciesForBean("data")).contains("kms");
                });
    }

    @Test
    void dataKeyReuseFromProperties() {
        runner.withPropertyValues("halyx.key-stores.kms.data-key-reuse.max-age=5m",
                        "halyx.key-stores.kms.data-key-reuse.max-messages=10000", "halyx.keys.data.name=orders")
                .withBean(BeanPostProcessor.class, () -> memoryKeys(Map.of("orders", KeyPurpose.ENCRYPTION)))
                .run(context -> {
                    CryptoKey data = context.getBean("data", CryptoKey.class);
                    data.encrypt(DATA, AAD);
                    data.encrypt(DATA, AAD);
                    KeyStore kms = context.getBean("kms", KeyStore.class);
                    assertThat(kms.statistics().wraps()).isEqualTo(1);
                    assertThat(kms.statistics().reusedDataKeys()).isEqualTo(1);
                });
    }

    @Test
    void theStoreMayBeOmittedWhenThereIsOne() {
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("data", CryptoKey.class).store()).isSameAs(context.getBean("kms"));
                    assertThat(context.getBean("kms", KeyStore.class).location()).contains("main");
                });
    }

    @Test
    void anAmbiguousOrUnknownStoreFailsTheStart() {
        runner.withPropertyValues("halyx.key-stores.a.location=a", "halyx.key-stores.b.location=b",
                        "halyx.keys.data.name=orders")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessage("halyx.keys.data.store is required when 2 key stores are configured [a, b]"));
        runner.withPropertyValues("halyx.key-stores.a.location=a", "halyx.keys.data.store=kms")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessage("halyx.keys.data.store must name one of halyx.key-stores [a]"));
        runner.withPropertyValues("halyx.keys.data.name=orders")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessage("halyx.keys.data.store is required when 0 key stores are configured []"));
    }

    @Test
    void settingsMistakesFailTheStartNamingTheProperty() {
        runner.withPropertyValues("halyx.key-stores.kms.segment-size=1KB")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageStartingWith("halyx.key-stores.kms: segmentSize must be"));
        runner.withPropertyValues("halyx.key-stores.kms.data-key-reuse.max-age=5m")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessage("halyx.key-stores.kms.data-key-reuse.max-messages is required"));
        runner.withPropertyValues("halyx.key-stores.kms.secrets[0].property=vault.token",
                        "halyx.key-stores.kms.secrets[0].store=app", "halyx.key-stores.kms.secrets[0].name=t")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageStartingWith("halyx.key-stores.kms.secrets: a key store takes no secrets"));
    }

    @Test
    void creatingTheBeansMakesNoKeyServiceCall() {
        // The key doesn't exist: without check-on-startup that shows in health and at first use, not at the start.
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=missing")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KeyStore kms = context.getBean("kms", KeyStore.class);
                    context.getBean("data", CryptoKey.class);
                    assertThat(kms.statistics().metadataReads()).isZero();
                });
    }

    @Test
    void checkOnStartupFailsTheStartForAMissingOrDisabledKey() {
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=missing",
                        "halyx.keys.data.check-on-startup=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(dev.halyx.error.NotFoundException.class));
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=missing",
                        "halyx.keys.data.check-on-startup=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("halyx.keys.data.check-on-startup: key 'missing' can't be used"));
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=orders",
                        "halyx.keys.data.check-on-startup=true")
                .withBean(BeanPostProcessor.class, () -> new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (bean instanceof KeyStore store) {
                            store.unwrap(InMemoryKeys.class).create("orders", KeyPurpose.ENCRYPTION);
                            store.unwrap(InMemoryKeys.class).disable("orders");
                        }
                        return bean;
                    }
                })
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessage("halyx.keys.data.check-on-startup: key 'orders' is disabled"));
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.tokens.name=orders",
                        "halyx.keys.tokens.purpose=signing", "halyx.keys.tokens.check-on-startup=true")
                .withBean(BeanPostProcessor.class, () -> memoryKeys(Map.of("orders", KeyPurpose.ENCRYPTION)))
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("halyx.keys.tokens.check-on-startup: key 'orders' can't be used"));
    }

    @Test
    void checkOnStartupPassesForAUsableKey() {
        runner.withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=orders",
                        "halyx.keys.data.check-on-startup=true")
                .withBean(BeanPostProcessor.class, () -> memoryKeys(Map.of("orders", KeyPurpose.ENCRYPTION)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("kms", KeyStore.class).statistics().metadataReads()).isEqualTo(1);
                });
    }

    @Test
    void withoutKeySettingsThereAreNoKeyBeans() {
        runner.withPropertyValues("halyx.blob-stores.invoices.bucket=acme-invoices")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(KeyStore.class)).isEmpty();
                    assertThat(context.getBeansOfType(CryptoKey.class)).isEmpty();
                });
    }

    @Test
    void reactiveViewsOfKeyStoresAndKeys() {
        runner.withConfiguration(AutoConfigurations.of(HalyxReactorAutoConfiguration.class))
                .withPropertyValues("halyx.key-stores.kms.location=main", "halyx.keys.data.name=orders")
                .withBean(BeanPostProcessor.class, () -> memoryKeys(Map.of("orders", KeyPurpose.ENCRYPTION)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReactiveKeyStore kms = context.getBean("kmsReactive", ReactiveKeyStore.class);
                    ReactiveCryptoKey data = context.getBean("dataReactive", ReactiveCryptoKey.class);

                    assertThat(data.blocking()).isSameAs(context.getBean("data"));
                    assertThat(kms.blocking()).isSameAs(context.getBean("kms"));
                    SealedData sealed = data.encrypt(DATA, AAD).block(Duration.ofSeconds(10));
                    assertThat(kms.decrypt(sealed, AAD).block(Duration.ofSeconds(10))).isEqualTo(DATA);
                });
    }
}
