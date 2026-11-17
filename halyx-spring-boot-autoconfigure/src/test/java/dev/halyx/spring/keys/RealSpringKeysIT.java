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
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.reactor.ReactiveCryptoKey;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import dev.halyx.spring.autoconfigure.security.HalyxJwtAutoConfiguration;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with a real cloud's key service (tier 1), on Boot 3.5 and 4: key stores and keys from properties, sealing
 * and signing on the cloud's keys, JWTs signed there, and the stores that use keys (an encrypted blob store, an encrypted
 * secrets store, a blob store with a server-side key). Runs only with {@code -Dhalyx.real.spring.keys=true}, from the
 * real-cloud scripts' tier 1, which pass:
 * <ul>
 *   <li>{@code halyx.real.spring.provider} and {@code .provider-properties}, as for the other Spring tests;</li>
 *   <li>{@code halyx.real.spring.bucket}, and {@code .secrets-location} where secrets need one (Azure's vault);</li>
 *   <li>{@code halyx.real.spring.key-store-location}: the long-lived keys' vault or key ring (empty on AWS);</li>
 *   <li>{@code halyx.real.spring.server-side-key-properties}: Spring properties, comma-separated, that the server-side
 *       key needs on this cloud (Azure's encryption scope; Google Cloud's regional key ring).</li>
 * </ul>
 * The keys are the long-lived {@code tck-*} ones in cloud-testing/infra/&lt;cloud&gt;/keys; secrets live under a prefix of
 * their own and are deleted at the end of each test.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.keys", matches = "true")
class RealSpringKeysIT {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class,
                    HalyxJwtAutoConfiguration.class))
            .withPropertyValues(baseProperties());

    private static String property(String name) {
        return System.getProperty("halyx.real.spring." + name, "");
    }

    private static List<String> list(String name) {
        return Arrays.stream(property(name).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String[] baseProperties() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + property("provider"),
                "halyx.key-stores.kms.prefix=tck-",
                "halyx.keys.data.store=kms",
                "halyx.keys.data.check-on-startup=true",
                "halyx.keys.tokens.store=kms",
                "halyx.keys.tokens.name=p256",
                "halyx.keys.tokens.signing-algorithm=ecdsa-p256-sha256"));
        if (!property("key-store-location").isBlank()) {
            properties.add("halyx.key-stores.kms.location=" + property("key-store-location"));
        }
        for (String pair : list("provider-properties")) {
            properties.add("halyx.providers.cloud.properties." + pair);
        }
        return properties.toArray(String[]::new);
    }

    @Test
    void keysFromPropertiesSealSignAndOpen() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            KeyStore kms = context.getBean("kms", KeyStore.class);
            CryptoKey data = context.getBean("data", CryptoKey.class);
            CryptoKey tokens = context.getBean("tokens", CryptoKey.class);
            ReactiveCryptoKey reactive = context.getBean("dataReactive", ReactiveCryptoKey.class);

            SealedData sealed = data.encrypt(DATA, AAD);
            Signature signature = tokens.sign(DATA);

            assertThat(sealed.keys().get(0).keyId()).isEqualTo(data.metadata().keyId());
            assertThat(kms.decrypt(sealed, AAD)).isEqualTo(DATA);
            assertThat(reactive.decrypt(sealed, AAD).block(Duration.ofSeconds(30))).isEqualTo(DATA);
            assertThat(tokens.verify(DATA, signature)).isTrue();
            assertThat(kms.statistics().metadataReads()).isPositive(); // check-on-startup read it
        });
    }

    @Test
    void jwtsAreSignedByTheKeyService() throws Exception {
        runner.withPropertyValues(JwtChecks.properties("tokens"))
                .run(context -> JwtChecks.signAndVerify(context, "ES256"));
    }

    @Test
    void anEncryptedBlobStoreStoresOnlySealedBlobs() {
        String bucket = property("bucket");
        BlobKey key = BlobKey.of("tck-spring-keys-" + UUID.randomUUID() + ".json");
        runner.withPropertyValues("halyx.blob-stores.files.bucket=" + bucket,
                "halyx.blob-stores.files.encryption-key.store=kms",
                "halyx.blob-stores.files.encryption-key.name=blobs",
                "halyx.blob-stores.raw.bucket=" + bucket).run(context -> {
            assertThat(context).hasNotFailed();
            BlobStore files = context.getBean("files", BlobStore.class);
            BlobStore raw = context.getBean("raw", BlobStore.class);
            try {
                files.put(key, BlobContent.ofBytes(DATA));

                assertThat(files.getBytes(key)).isEqualTo(DATA);
                assertThat(new String(raw.getBytes(key), StandardCharsets.ISO_8859_1))
                        .as("only sealed bytes are stored").doesNotContain("ana@example.com");
            } finally {
                raw.delete(key);
            }
        });
    }

    @Test
    void anEncryptedSecretsStoreStoresOnlySealedValues() {
        String prefix = "tck-spring-keys-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> properties = new ArrayList<>(List.of(
                "halyx.parameter-stores.config.kind=secrets",
                "halyx.parameter-stores.config.prefix=" + prefix,
                "halyx.parameter-stores.config.encryption-key.store=kms",
                "halyx.parameter-stores.config.encryption-key.name=parameters",
                "halyx.parameter-stores.raw.kind=secrets",
                "halyx.parameter-stores.raw.prefix=" + prefix));
        if (!property("secrets-location").isBlank()) {
            properties.add("halyx.parameter-stores.config.location=" + property("secrets-location"));
            properties.add("halyx.parameter-stores.raw.location=" + property("secrets-location"));
        }
        runner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            ParameterStore config = context.getBean("config", ParameterStore.class);
            ParameterStore raw = context.getBean("raw", ParameterStore.class);
            CryptoKey parameters = context.getBean("kms", KeyStore.class).key("parameters");
            try {
                config.put("password", "s3cr3t");

                String stored = raw.require("password");
                assertThat(stored).startsWith("hlxs1:");
                assertThat(SealedData.parseText(stored).keys().get(0).keyId())
                        .isEqualTo(parameters.metadata().keyId());
                assertThat(config.require("password")).isEqualTo("s3cr3t");
            } finally {
                raw.delete("password");
            }
        });
    }

    @Test
    void aBlobStoreWithAServerSideKeyRoundTrips() {
        BlobKey key = BlobKey.of("tck-spring-keys-" + UUID.randomUUID() + ".json");
        List<String> properties = new ArrayList<>(List.of(
                "halyx.blob-stores.receipts.bucket=" + property("bucket"),
                "halyx.blob-stores.receipts.server-side-key.store=kms",
                "halyx.blob-stores.receipts.server-side-key.name=server-side"));
        properties.addAll(list("server-side-key-properties"));
        runner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            BlobStore receipts = context.getBean("receipts", BlobStore.class);
            try {
                receipts.put(key, BlobContent.ofBytes(DATA));

                assertThat(receipts.getBytes(key)).isEqualTo(DATA);
            } finally {
                receipts.delete(key);
            }
        });
    }
}
