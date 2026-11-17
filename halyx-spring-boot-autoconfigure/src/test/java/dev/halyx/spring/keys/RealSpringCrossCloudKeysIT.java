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

import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.CryptoKeys;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.SealedHeader;
import dev.halyx.keys.WrappedKey;
import dev.halyx.reactor.ReactiveCryptoKey;
import dev.halyx.reactor.ReactiveCryptoKeys;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One envelope for keys on three clouds at once (tier 1 across clouds), on Boot 3.5 and 4: data sealed for an AWS KMS
 * key, an Azure Key Vault key and a Google Cloud KMS key opens on each cloud alone, and keys are added, moved and
 * removed with the body untouched. Key stores and keys come from Spring properties, one provider per cloud in one
 * application. Runs only with {@code -Dhalyx.real.spring.cross-cloud-keys=true}, from
 * cloud-testing/scripts/cross-cloud-keys.sh, which passes:
 * <ul>
 *   <li>{@code halyx.real.spring.aws-region}: where the long-lived AWS keys are;</li>
 *   <li>{@code halyx.real.spring.azure-vault-url}: the long-lived keys' Key Vault;</li>
 *   <li>{@code halyx.real.spring.gcp-project} and {@code .gcp-key-ring}: the long-lived global key ring.</li>
 * </ul>
 * The keys are the long-lived {@code tck-data} keys in cloud-testing/infra/&lt;cloud&gt;/keys. Nothing is created in
 * any cloud, so there is nothing to delete.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.cross-cloud-keys", matches = "true")
class RealSpringCrossCloudKeysIT {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    /** Several segments, and far more body than header, so the body's bytes can be compared on their own. */
    private static final byte[] DATA = random(200 * 1024);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
            .withPropertyValues(
                    "halyx.providers.aws.type=aws",
                    "halyx.providers.aws.region=" + property("aws-region"),
                    "halyx.providers.azure.type=azure",
                    "halyx.providers.gcp.type=gcp",
                    "halyx.providers.gcp.properties.gcp.project-id=" + property("gcp-project"),
                    "halyx.key-stores.awskms.provider=aws",
                    "halyx.key-stores.awskms.prefix=tck-",
                    "halyx.key-stores.keyvault.provider=azure",
                    "halyx.key-stores.keyvault.location=" + property("azure-vault-url"),
                    "halyx.key-stores.keyvault.prefix=tck-",
                    "halyx.key-stores.cloudkms.provider=gcp",
                    "halyx.key-stores.cloudkms.location=" + property("gcp-key-ring"),
                    "halyx.key-stores.cloudkms.prefix=tck-",
                    "halyx.keys.onaws.store=awskms",
                    "halyx.keys.onaws.name=data",
                    "halyx.keys.onaws.check-on-startup=true",
                    "halyx.keys.onazure.store=keyvault",
                    "halyx.keys.onazure.name=data",
                    "halyx.keys.onazure.check-on-startup=true",
                    "halyx.keys.ongcp.store=cloudkms",
                    "halyx.keys.ongcp.name=data",
                    "halyx.keys.ongcp.check-on-startup=true");

    private static String property(String name) {
        return System.getProperty("halyx.real.spring." + name, "");
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** The three clouds' keys and stores, from one application context. */
    private record Clouds(CryptoKey aws, CryptoKey azure, CryptoKey gcp, KeyStore awsStore, KeyStore azureStore,
                          KeyStore gcpStore) {

        static Clouds of(AssertableApplicationContext context) {
            assertThat(context).hasNotFailed();
            return new Clouds(context.getBean("onaws", CryptoKey.class), context.getBean("onazure", CryptoKey.class),
                    context.getBean("ongcp", CryptoKey.class), context.getBean("awskms", KeyStore.class),
                    context.getBean("keyvault", KeyStore.class), context.getBean("cloudkms", KeyStore.class));
        }

        List<CryptoKey> keys() {
            return List.of(aws, azure, gcp);
        }

        List<KeyStore> stores() {
            return List.of(awsStore, azureStore, gcpStore);
        }
    }

    @Test
    void dataSealedForThreeCloudsOpensOnEachCloudAlone() {
        runner.run(context -> {
            Clouds clouds = Clouds.of(context);

            SealedData sealed = CryptoKeys.encrypt(DATA, AAD, clouds.aws(), clouds.azure(), clouds.gcp());

            assertThat(keyIds(sealed)).containsExactly(keyId(clouds.aws()), keyId(clouds.azure()),
                    keyId(clouds.gcp()));
            for (int i = 0; i < 3; i++) {
                SealedData alone = only(sealed, clouds.keys().get(i));
                assertThat(alone.keys()).hasSize(1);
                assertThat(clouds.keys().get(i).decrypt(alone, AAD)).isEqualTo(DATA);
                assertThat(clouds.stores().get(i).decrypt(alone, AAD)).isEqualTo(DATA);
                for (int j = 0; j < 3; j++) {
                    KeyStore other = clouds.stores().get(j);
                    if (j != i) {
                        assertThatThrownBy(() -> other.decrypt(alone, AAD))
                                .as("another cloud can't open the data alone")
                                .isInstanceOf(InvalidArgumentException.class);
                    }
                }
            }
            assertThatThrownBy(() -> clouds.gcp().decrypt(sealed, "orders/o-2".getBytes(StandardCharsets.UTF_8)))
                    .as("the AAD is still checked").isInstanceOf(DataIntegrityException.class);
        });
    }

    @Test
    void addToMovesDataOntoAnotherCloudWithoutTouchingTheBody() {
        runner.run(context -> {
            Clouds clouds = Clouds.of(context);
            SealedData sealed = CryptoKeys.encrypt(DATA, AAD, clouds.aws(), clouds.azure());

            SealedData added = clouds.gcp().addTo(sealed, clouds.awsStore());
            SealedData onGcp = added.withoutKey(keyId(clouds.aws())).withoutKey(keyId(clouds.azure()));

            assertThat(keyIds(added)).containsExactly(keyId(clouds.aws()), keyId(clouds.azure()),
                    keyId(clouds.gcp()));
            assertThat(body(added)).isEqualTo(body(sealed));
            assertThat(body(onGcp)).isEqualTo(body(sealed));
            assertThat(clouds.gcpStore().decrypt(onGcp, AAD)).isEqualTo(DATA);
            assertThatThrownBy(() -> clouds.awsStore().decrypt(onGcp, AAD))
                    .isInstanceOf(InvalidArgumentException.class);

            // and back, from Google Cloud alone onto Azure, opened through the Azure key's entry only
            SealedData backOnAzure = clouds.azure().addTo(onGcp, clouds.gcpStore()).withoutKey(keyId(clouds.gcp()));
            assertThat(body(backOnAzure)).isEqualTo(body(sealed));
            assertThat(clouds.azureStore().decrypt(backOnAzure, AAD)).isEqualTo(DATA);
        });
    }

    @Test
    void rewrapReplacesOneCloudsEntryWithAnothers() {
        runner.run(context -> {
            Clouds clouds = Clouds.of(context);
            SealedData sealed = CryptoKeys.encrypt(DATA, AAD, clouds.aws(), clouds.azure());

            SealedData moved = clouds.gcp().rewrap(sealed, clouds.azureStore());

            assertThat(keyIds(moved)).containsExactlyInAnyOrder(keyId(clouds.aws()), keyId(clouds.gcp()));
            assertThat(body(moved)).isEqualTo(body(sealed));
            assertThat(clouds.gcpStore().decrypt(only(moved, clouds.gcp()), AAD)).isEqualTo(DATA);
            assertThat(clouds.awsStore().decrypt(only(moved, clouds.aws()), AAD)).isEqualTo(DATA);
            assertThatThrownBy(() -> clouds.azureStore().decrypt(moved, AAD))
                    .isInstanceOf(InvalidArgumentException.class);
        });
    }

    @Test
    void aStreamSealedForThreeCloudsOpensOnEach() {
        runner.run(context -> {
            Clouds clouds = Clouds.of(context);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (OutputStream out = CryptoKeys.encryptingStream(sink, AAD, clouds.aws(), clouds.azure(),
                    clouds.gcp())) {
                out.write(DATA);
            }

            for (CryptoKey key : clouds.keys()) {
                SealedData alone = only(SealedData.parse(sink.toByteArray()), key);
                try (InputStream in = key.store().decryptingStream(new ByteArrayInputStream(alone.toBytes()), AAD)) {
                    assertThat(in.readAllBytes()).isEqualTo(DATA);
                }
            }
        });
    }

    @Test
    void theReactiveViewSealsForThreeClouds() {
        runner.run(context -> {
            Clouds clouds = Clouds.of(context);
            ReactiveCryptoKey aws = context.getBean("onawsReactive", ReactiveCryptoKey.class);
            ReactiveCryptoKey azure = context.getBean("onazureReactive", ReactiveCryptoKey.class);
            ReactiveCryptoKey gcp = context.getBean("ongcpReactive", ReactiveCryptoKey.class);

            SealedData sealed = ReactiveCryptoKeys.encrypt(DATA, AAD, aws, azure, gcp).block(Duration.ofSeconds(60));

            assertThat(sealed).isNotNull();
            assertThat(keyIds(sealed)).hasSize(3);
            assertThat(azure.decrypt(only(sealed, clouds.azure()), AAD).block(Duration.ofSeconds(60)))
                    .isEqualTo(DATA);
            assertThat(clouds.aws().decrypt(only(sealed, clouds.aws()), AAD)).isEqualTo(DATA);
            assertThat(clouds.gcp().decrypt(only(sealed, clouds.gcp()), AAD)).isEqualTo(DATA);
        });
    }

    private static String keyId(CryptoKey key) {
        return key.metadata().keyId();
    }

    private static List<String> keyIds(SealedData sealed) {
        return sealed.keys().stream().map(WrappedKey::keyId).toList();
    }

    /** {@code sealed} with every entry but {@code key}'s removed. */
    private static SealedData only(SealedData sealed, CryptoKey key) {
        SealedData alone = sealed;
        for (String keyId : keyIds(sealed)) {
            if (!keyId.equals(keyId(key))) {
                alone = alone.withoutKey(keyId);
            }
        }
        return alone;
    }

    /** The encrypted body: everything after the header. */
    private static byte[] body(SealedData sealed) {
        byte[] bytes = sealed.toBytes();
        int header = SealedHeader.read(new ByteArrayInputStream(bytes)).length();
        return Arrays.copyOfRange(bytes, header, bytes.length);
    }
}
