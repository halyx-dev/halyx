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

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.reactor.ReactiveBlobStore;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.StorageCapability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Blob stores sealed with a key from properties, and the same stores without one, on Boot 3.5 and 4. */
class HalyxEncryptedBlobStoresTest {

    private static final String TEXT = "{\"email\":\"ana@example.com\"}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory")
            .withBean(BeanPostProcessor.class, HalyxEncryptedBlobStoresTest::memoryKeys);

    /** Creates the memory key {@code receipts} in each key store as it is made. */
    private static BeanPostProcessor memoryKeys() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KeyStore store) {
                    store.unwrap(InMemoryKeys.class).create("receipts", KeyPurpose.ENCRYPTION);
                }
                return bean;
            }
        };
    }

    @Test
    void aBlobStoreWithAKeySealsWhatItWrites() {
        runner.withPropertyValues(
                "halyx.blob-stores.receipts.bucket=orders",
                "halyx.blob-stores.receipts.encryption-key.name=receipts",
                "halyx.blob-stores.plain.bucket=orders",
                "halyx.key-stores.kms.location=tests").run(context -> {
            assertThat(context).hasNotFailed();
            BlobStore receipts = context.getBean("receipts", BlobStore.class);
            BlobStore plain = context.getBean("plain", BlobStore.class);
            ReactiveBlobStore reactive = context.getBean("receiptsReactive", ReactiveBlobStore.class);
            BlobKey key = BlobKey.of("o-1/receipt.json");

            receipts.put(key, BlobContent.ofString(TEXT));

            assertThat(receipts.getBytes(key)).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
            assertThat(new String(plain.getBytes(key), 0, 4, StandardCharsets.US_ASCII))
                    .as("the same bucket, read without the key").isEqualTo("HLXS");
            assertThat(reactive.getBytes(key).block(Duration.ofSeconds(10))).asString(StandardCharsets.UTF_8)
                    .isEqualTo(TEXT);
            assertThat(receipts.capabilities().supports(StorageCapability.PRESIGNED_URL)).isFalse();
            assertThat(context.getBean("kms", KeyStore.class).statistics().wraps()).isEqualTo(1);
        });
    }

    @Test
    void aPrefixSealsOnlyUnderIt() {
        runner.withPropertyValues(
                "halyx.blob-stores.files.encryption-key.store=kms",
                "halyx.blob-stores.files.encryption-key.name=receipts",
                "halyx.blob-stores.files.encryption-prefix=private/",
                "halyx.blob-stores.raw.bucket=files",
                "halyx.key-stores.kms.location=tests").run(context -> {
            BlobStore files = context.getBean("files", BlobStore.class);
            BlobStore raw = context.getBean("raw", BlobStore.class);
            files.put(BlobKey.of("private/a.txt"), BlobContent.ofString(TEXT));
            files.put(BlobKey.of("public/a.txt"), BlobContent.ofString(TEXT));

            assertThat(files.getBytes(BlobKey.of("private/a.txt"))).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
            assertThat(new String(raw.getBytes(BlobKey.of("private/a.txt")), 0, 4, StandardCharsets.US_ASCII))
                    .isEqualTo("HLXS");
            assertThat(raw.getBytes(BlobKey.of("public/a.txt"))).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
            assertThat(files.capabilities().supports(StorageCapability.PRESIGNED_URL))
                    .as("a prefix view keeps the store's presigned URLs for the keys outside its prefix")
                    .isEqualTo(raw.capabilities().supports(StorageCapability.PRESIGNED_URL));
        });
    }

    @Test
    void withoutAKeyNothingChanges() {
        runner.withPropertyValues("halyx.blob-stores.files.bucket=orders").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(KeyStore.class)).as("no key store is opened").isEmpty();
            BlobStore files = context.getBean("files", BlobStore.class);
            files.put(BlobKey.of("a.txt"), BlobContent.ofString(TEXT));
            assertThat(files.getBytes(BlobKey.of("a.txt"))).asString(StandardCharsets.UTF_8).isEqualTo(TEXT);
            assertThat(files.toString()).as("the store itself, not an encrypted view").doesNotContain("Encrypted");
        });
    }

    @Test
    void aPlainBlobIsRefusedThroughTheEncryptedStore() {
        runner.withPropertyValues(
                "halyx.blob-stores.receipts.bucket=orders",
                "halyx.blob-stores.receipts.encryption-key.name=receipts",
                "halyx.blob-stores.plain.bucket=orders",
                "halyx.key-stores.kms.location=tests").run(context -> {
            context.getBean("plain", BlobStore.class).put(BlobKey.of("old.txt"), BlobContent.ofString("legacy"));

            assertThatThrownBy(() -> context.getBean("receipts", BlobStore.class).getBytes(BlobKey.of("old.txt")))
                    .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("isn't encrypted");
        });
    }

    @Test
    void misconfigurationFailsTheStart() {
        runner.withPropertyValues("halyx.blob-stores.files.encryption-prefix=private/").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.blob-stores.files.encryption-prefix needs halyx.blob-stores.files.encryption-key"));
        runner.withPropertyValues("halyx.blob-stores.files.encryption-key.name=receipts").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.blob-stores.files.encryption-key.store is required when 0 key stores are configured"));
        runner.withPropertyValues("halyx.blob-stores.files.encryption-key.store=kms",
                "halyx.key-stores.kms.location=tests").run(context ->
                assertThat(context).getFailure().hasStackTraceContaining(
                        "halyx.blob-stores.files.encryption-key.name is required"));
    }
}
