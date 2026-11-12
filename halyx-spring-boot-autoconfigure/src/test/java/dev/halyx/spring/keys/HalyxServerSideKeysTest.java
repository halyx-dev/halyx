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
import dev.halyx.memory.InMemoryBlobs;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.memory.InMemoryParameters;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.HalyxProperties;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blob stores the storage service encrypts with a key from properties, alone and under client-side sealing, and
 * parameter stores whose service does.
 */
class HalyxServerSideKeysTest {

    private static final String TEXT = "{\"email\":\"ana@example.com\"}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory", "halyx.key-stores.kms.location=tests",
                    "halyx.blob-stores.raw.bucket=orders")
            .withBean(BeanPostProcessor.class, HalyxServerSideKeysTest::memoryKeys);

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

    private static String storedKey(BlobStore store, String key) {
        return store.unwrap(InMemoryBlobs.class).serverSideKey(BlobKey.of(key)).orElse(null);
    }

    @Test
    void aServerSideKeyEncryptsEveryWrite() {
        runner.withPropertyValues("halyx.blob-stores.receipts.bucket=orders",
                "halyx.blob-stores.receipts.server-side-key.name=storage").run(context -> {
            assertThat(context).hasNotFailed();
            BlobStore receipts = context.getBean("receipts", BlobStore.class);
            BlobStore raw = context.getBean("raw", BlobStore.class);
            KeyStore kms = context.getBean("kms", KeyStore.class);

            receipts.put(BlobKey.of("r-1.json"), BlobContent.ofString(TEXT));
            raw.put(BlobKey.of("plain.json"), BlobContent.ofString(TEXT));

            assertThat(storedKey(raw, "r-1.json")).isEqualTo(kms.key("storage").metadata().nativeId());
            assertThat(storedKey(raw, "plain.json")).isNull();
            assertThat(raw.getBytes(BlobKey.of("r-1.json"))).asString(StandardCharsets.UTF_8)
                    .as("the service decrypts on read").isEqualTo(TEXT);
            assertThat(kms.statistics().wraps()).as("no client-side sealing").isZero();
        });
    }

    @Test
    void bothKeysSealAndEncryptAtRest() {
        runner.withPropertyValues("halyx.blob-stores.receipts.bucket=orders",
                "halyx.blob-stores.receipts.server-side-key.name=storage",
                "halyx.blob-stores.receipts.encryption-key.name=sealing").run(context -> {
            BlobStore receipts = context.getBean("receipts", BlobStore.class);
            BlobStore raw = context.getBean("raw", BlobStore.class);

            receipts.put(BlobKey.of("r-1.json"), BlobContent.ofString(TEXT));

            assertThat(receipts.getBytes(BlobKey.of("r-1.json"))).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
            assertThat(new String(raw.getBytes(BlobKey.of("r-1.json")), 0, 4, StandardCharsets.US_ASCII))
                    .isEqualTo("HLXS");
            assertThat(storedKey(raw, "r-1.json"))
                    .isEqualTo(context.getBean("kms", KeyStore.class).key("storage").metadata().nativeId());
        });
    }

    @Test
    void keyOptionsBindWithDottedNames() {
        var source = new MapConfigurationPropertySource(Map.of(
                "halyx.blob-stores.files.server-side-key.name", "storage",
                "halyx.blob-stores.files.server-side-key.properties[azure.storage.encryption-scope]", "orders-scope"));

        var stores = new Binder(source).bind("halyx.blob-stores",
                Bindable.mapOf(String.class, HalyxProperties.BlobStoreProperties.class)).get();

        assertThat(stores.get("files").serverSideKey().properties())
                .containsExactly(Map.entry("azure.storage.encryption-scope", "orders-scope"));
    }

    @Test
    void misconfigurationFailsTheStart() {
        runner.withPropertyValues("halyx.blob-stores.files.server-side-key.store=kms").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.blob-stores.files.server-side-key.name is required"));
        runner.withPropertyValues("halyx.blob-stores.files.server-side-key.name=storage",
                "halyx.blob-stores.files.server-side-key.store=nope").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining("halyx.blob-stores.files.server-side-key.store"));
    }

    @Test
    void aServerSideKeyEncryptsNewParameters() {
        runner.withPropertyValues("halyx.parameter-stores.secrets.kind=secrets",
                "halyx.parameter-stores.secrets.prefix=orders",
                "halyx.parameter-stores.secrets.server-side-key.name=storage",
                "halyx.parameter-stores.plain.kind=secrets").run(context -> {
            assertThat(context).hasNotFailed();
            KeyStore kms = context.getBean("kms", KeyStore.class);
            ParameterStore secrets = context.getBean("secrets", ParameterStore.class);
            ParameterStore plain = context.getBean("plain", ParameterStore.class);
            assertThat(kms.statistics().metadataReads()).as("no key service call at startup").isZero();
            assertThat(context.getBeanFactory().getBeanDefinition("secrets").getDescription())
                    .endsWith("encrypted at rest with storage");

            secrets.put("db/password", "s3cr3t");
            plain.put("api/token", "t0k3n");

            InMemoryParameters stored = plain.unwrap(InMemoryParameters.class);
            assertThat(stored.serverSideKey("orders/db/password")).contains(kms.key("storage").metadata().nativeId());
            assertThat(stored.serverSideKey("api/token")).isEmpty();
            assertThat(plain.require("orders/db/password")).as("reads need nothing").isEqualTo("s3cr3t");
        });
    }

    @Test
    void aParameterStoreKeyIsCheckedAtStartup() {
        runner.withPropertyValues("halyx.parameter-stores.secrets.server-side-key.store=kms").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.parameter-stores.secrets.server-side-key.name is required"));
        runner.withPropertyValues("halyx.parameter-stores.secrets.server-side-key.name=storage",
                "halyx.parameter-stores.secrets.server-side-key.store=nope").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.parameter-stores.secrets.server-side-key.store must name one of halyx.key-stores"));
    }
}
