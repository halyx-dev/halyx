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
package dev.halyx.keys;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.memory.InMemoryKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyStoreTest {

    private static final byte[] AAD = "orders/o-1001".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);

    private final List<String> operations = new CopyOnWriteArrayList<>();
    private Halyx halyx;
    private KeyStore store;
    private InMemoryKeys keys;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("local", ProviderConfig.of("memory"))
                .register("other", ProviderConfig.of("memory"))
                .interceptor(new Interceptor() {
                    @Override
                    public <T> T intercept(OperationContext context, Invocation<T> next) {
                        operations.add(context.service() + ":" + context.operation());
                        return next.proceed();
                    }
                })
                .build();
        store = halyx.provider("local").keyStore();
        keys = store.unwrap(InMemoryKeys.class);
        keys.create("data", KeyPurpose.ENCRYPTION);
        keys.create("other-data", KeyPurpose.ENCRYPTION);
        keys.create("tags", KeyPurpose.MAC);
        keys.create("rsa", KeyPurpose.SIGNING, SigningAlgorithm.RSA_PSS_SHA256);
        keys.create("p256", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
        keys.create("p384", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P384_SHA384);
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    @Test
    void encryptsAndDecryptsWithOneWrapAndOneUnwrap() {
        CryptoKey data = store.key("data");
        assertThat(operations).as("no call until first use").isEmpty();

        SealedData sealed = data.encrypt(DATA, AAD);

        assertThat(data.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(store.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(sealed.keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo("memory:/data");
            assertThat(entry.version()).contains("1");
            assertThat(entry.algorithm()).contains(WrapAlgorithm.MEMORY);
        });
        assertThat(operations).containsExactly("keys:metadata", "keys:wrap", "keys:unwrap", "keys:unwrap");
        assertThat(store.statistics().wraps()).isEqualTo(1);
        assertThat(store.statistics().unwraps()).isEqualTo(2);
    }

    @Test
    void aadIsBoundAndOptional() {
        CryptoKey data = store.key("data");

        assertThat(data.decrypt(data.encrypt(DATA, new byte[0]), new byte[0])).isEqualTo(DATA);
        assertThatThrownBy(() -> data.decrypt(data.encrypt(DATA, AAD), "orders/o-1002".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DataIntegrityException.class);
        assertThat(data.decrypt(data.encrypt(new byte[0], AAD), AAD)).isEmpty();
    }

    @Test
    void largePlaintextsSpanSegments() {
        KeyStore small = halyx.provider("local").keyStore(KeyStoreConfig.builder().segmentSize(4096).build());
        byte[] large = random(50_000);

        SealedData sealed = small.key("data").encrypt(large, AAD);

        assertThat(sealed.toBytes().length).isEqualTo(sealed.header().length() + 50_000 + 13 * 16);
        assertThat(small.key("data").decrypt(sealed, AAD)).isEqualTo(large);
        assertThat(store.decrypt(sealed, AAD)).as("segment size travels in the header").isEqualTo(large);
    }

    @Test
    void dataSealedUnderAnotherKeyIsRefusedBeforeAnyUnwrap() {
        SealedData sealed = store.key("data").encrypt(DATA, AAD);
        operations.clear();

        assertThatThrownBy(() -> store.key("other-data").decrypt(sealed, AAD))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("isn't sealed under key other-data");
        assertThat(operations).doesNotContain("keys:unwrap");

        KeyStore elsewhere = halyx.provider("local").keyStore(KeyStoreConfig.builder().location("ring-2").build());
        assertThatThrownBy(() -> elsewhere.decrypt(sealed, AAD)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("aren't in");
    }

    @Test
    void aReplacedKeyEntryFailsTheCommitment() {
        CryptoKey data = store.key("data");
        SealedData sealed = data.encrypt(DATA, AAD);
        WrappedKey forged = data.generateDataKey().wrapped(); // a valid wrap of a different data key

        SealedData tampered = sealed.withKeys(List.of(forged));

        assertThatThrownBy(() -> data.decrypt(tampered, AAD)).isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("key commitment");
    }

    @Test
    void entriesWithAnAlgorithmTheDriverDoesntImplementAreSkipped() {
        CryptoKey data = store.key("data");
        SealedData sealed = data.encrypt(DATA, AAD);
        WrappedKey own = sealed.keys().get(0);
        WrappedKey unknown = new WrappedKey(own.keyId(), "1", 239, own.bytes());
        WrappedKey foreign = new WrappedKey(own.keyId(), Optional.of("1"), WrapAlgorithm.AWS_KMS, own.bytes());

        for (WrappedKey entry : List.of(unknown, foreign)) {
            SealedData only = sealed.withKeys(List.of(entry));
            assertThatThrownBy(() -> store.decrypt(only, AAD)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> data.decrypt(only, AAD)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> store.decryptDataKey(entry)).isInstanceOf(InvalidArgumentException.class);
        }
        WrappedKey other = store.key("other-data").encrypt(DATA, AAD).keys().get(0);
        SealedData skipping = sealed.withKeys(List.of(new WrappedKey(other.keyId(), "1", 239, other.bytes()), own));
        assertThat(store.decrypt(skipping, AAD)).isEqualTo(DATA);
    }

    @Test
    void dataKeysForApplicationsWithTheirOwnFormat() {
        CryptoKey data = store.key("data");

        try (DataKey generated = data.generateDataKey();
             DataKey unwrapped = data.decryptDataKey(generated.wrapped());
             DataKey viaStore = store.decryptDataKey(generated.wrapped())) {
            assertThat(generated.plaintext()).hasSize(32).isEqualTo(unwrapped.plaintext()).isEqualTo(viaStore.plaintext());
            assertThat(generated.toString()).doesNotContain(Arrays.toString(generated.plaintext()));
        }
        DataKey closed = data.generateDataKey();
        closed.close();
        assertThatThrownBy(closed::plaintext).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void streamsSealAndOpenOneSegmentAtATime() throws IOException {
        KeyStore small = halyx.provider("local").keyStore(KeyStoreConfig.builder().segmentSize(4096).build());
        byte[] large = random(20_000);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = small.key("data").encryptingStream(sink, AAD)) {
            for (int i = 0; i < large.length; i += 1000) {
                out.write(large, i, 1000);
            }
        }

        try (InputStream in = small.decryptingStream(new ByteArrayInputStream(sink.toByteArray()), AAD)) {
            assertThat(in.readAllBytes()).isEqualTo(large);
        }
        assertThat(small.decrypt(SealedData.parse(sink.toByteArray()), AAD)).isEqualTo(large);

        byte[] truncated = Arrays.copyOf(sink.toByteArray(), sink.size() - 4096 - 16);
        assertThatThrownBy(() -> {
            try (InputStream in = small.decryptingStream(new ByteArrayInputStream(truncated), AAD)) {
                in.readAllBytes();
            }
        }).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void anExactMultipleOfTheSegmentSizeAndAnEmptyStreamRoundTrip() throws IOException {
        KeyStore small = halyx.provider("local").keyStore(KeyStoreConfig.builder().segmentSize(4096).build());
        for (int length : new int[] {0, 4096, 8192}) {
            byte[] plaintext = random(length);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (OutputStream out = small.key("data").encryptingStream(sink, AAD)) {
                out.write(plaintext);
            }
            try (InputStream in = small.decryptingStream(new ByteArrayInputStream(sink.toByteArray()), AAD)) {
                assertThat(in.readAllBytes()).as("length %d", length).isEqualTo(plaintext);
            }
        }
    }

    @Test
    void sealsForSeveralKeysAcrossStoresAndProviders() {
        CryptoKey local = store.key("data");
        KeyStore otherStore = halyx.provider("other").keyStore(KeyStoreConfig.builder().location("ring-2").build());
        otherStore.unwrap(InMemoryKeys.class).create("backup", KeyPurpose.ENCRYPTION);
        CryptoKey backup = otherStore.key("backup");

        SealedData sealed = CryptoKeys.encrypt(DATA, AAD, local, backup);

        assertThat(sealed.keys()).extracting(WrappedKey::keyId).containsExactly("memory:/data", "memory:ring-2/backup");
        assertThat(local.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(backup.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(otherStore.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(backup.decrypt(sealed.withoutKey("memory:/data"), AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> CryptoKeys.encrypt(DATA, AAD, local, local))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("more than once");
    }

    @Test
    void rewrapAfterRotationChangesOnlyTheHeader() {
        CryptoKey data = store.key("data");
        SealedData sealed = data.encrypt(random(200_000), AAD);
        keys.rotate("data");
        refresh(data);

        SealedData rewrapped = data.rewrap(sealed);

        assertThat(rewrapped.keys()).singleElement().satisfies(entry -> assertThat(entry.version()).contains("2"));
        assertThat(body(rewrapped)).isEqualTo(body(sealed));
        assertThat(data.decrypt(rewrapped, AAD)).isEqualTo(data.decrypt(sealed, AAD));
    }

    @Test
    void rewrapAndAddToAcrossStores() {
        CryptoKey from = store.key("data");
        KeyStore otherStore = halyx.provider("other").keyStore(KeyStoreConfig.builder().location("ring-2").build());
        otherStore.unwrap(InMemoryKeys.class).create("target", KeyPurpose.ENCRYPTION);
        CryptoKey target = otherStore.key("target");
        SealedData sealed = from.encrypt(DATA, AAD);

        SealedData moved = target.rewrap(sealed, store);
        SealedData both = target.addTo(sealed, store);

        assertThat(moved.keys()).extracting(WrappedKey::keyId).containsExactly("memory:ring-2/target");
        assertThat(target.decrypt(moved, AAD)).isEqualTo(DATA);
        assertThat(both.keys()).extracting(WrappedKey::keyId).containsExactly("memory:/data", "memory:ring-2/target");
        assertThat(from.decrypt(both, AAD)).isEqualTo(DATA);
        assertThat(target.decrypt(both, AAD)).isEqualTo(DATA);
        assertThat(body(both)).isEqualTo(body(sealed));
        assertThat(target.addTo(both, store).keys()).as("adding again replaces the entry").hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(SigningAlgorithm.class)
    void signsAndVerifiesLocally(SigningAlgorithm algorithm) {
        String name = switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> "rsa";
            case ECDSA_P256_SHA256 -> "p256";
            case ECDSA_P384_SHA384 -> "p384";
        };
        CryptoKey key = store.key(name, KeyOptions.builder().signingAlgorithm(algorithm).build());

        Signature signature = key.sign(DATA);
        operations.clear();

        assertThat(key.verify(DATA, signature)).isTrue();
        assertThat(key.verify(DATA, Signature.parse(signature.toBytes()))).isTrue();
        assertThat(key.verify("other".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        byte[] bad = signature.bytes();
        bad[bad.length - 1] ^= 1;
        assertThat(key.verify(DATA, new Signature(bad, algorithm, signature.keyVersion()))).isFalse();
        assertThat(operations).as("verification makes no key service call once the public key is cached")
                .containsOnly("keys:publicKey").hasSizeLessThanOrEqualTo(1);
        assertThat(key.signDigest(DefaultCryptoKey.digest(algorithm, DATA)).algorithm()).isEqualTo(algorithm);
        assertThatThrownBy(() -> key.signDigest(new byte[5])).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void signaturesFromAPreviousVersionStillVerify() {
        CryptoKey key = store.key("p256");
        Signature before = key.sign(DATA);
        keys.rotate("p256");
        refresh(key);

        Signature after = key.sign(DATA);

        assertThat(before.keyVersion()).contains("1");
        assertThat(after.keyVersion()).contains("2");
        assertThat(key.verify(DATA, before)).isTrue();
        assertThat(key.verify(DATA, after)).isTrue();
        assertThat(key.publicKeys()).containsOnlyKeys("1", "2");
        assertThat(key.verify(DATA, new Signature(before.bytes(), before.algorithm(), java.util.Optional.of("9"))))
                .as("a version the key never had").isFalse();
    }

    @Test
    void rsaKeysNeedTheirSigningAlgorithmChosen() {
        assertThatThrownBy(() -> store.key("rsa").sign(DATA)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("KeyOptions.signingAlgorithm");
        assertThatThrownBy(() -> store.key("p256",
                KeyOptions.builder().signingAlgorithm(SigningAlgorithm.ECDSA_P384_SHA384).build()).sign(DATA))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("can't sign with");
    }

    @Test
    void purposesAreChecked() {
        assertThatThrownBy(() -> store.key("p256").encrypt(DATA, AAD)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("can't be used for ENCRYPTION");
        assertThatThrownBy(() -> store.key("data").sign(DATA)).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> store.key("data", KeyOptions.builder().purpose(KeyPurpose.SIGNING).build()).metadata())
                .isInstanceOf(InvalidArgumentException.class);
        assertThat(store.key("p256").capabilities().supports(KeyCapability.SIGN)).isTrue();
        assertThat(store.key("data").capabilities().supports(KeyCapability.SIGN)).isFalse();
    }

    @Test
    void macsAreMadeAndVerifiedByTheKeyService() {
        CryptoKey tags = store.key("tags");

        byte[] mac = tags.mac(DATA);

        assertThat(mac).hasSize(32);
        assertThat(tags.verifyMac(DATA, mac)).isTrue();
        assertThat(tags.verifyMac("other".getBytes(StandardCharsets.UTF_8), mac)).isFalse();
        assertThat(tags.capabilities().supports(KeyCapability.MAC)).isTrue();
        assertThat(store.statistics().macs()).isEqualTo(3);
    }

    @Test
    void missingAndDisabledKeysFailThePortableWay() {
        assertThatThrownBy(() -> store.key("nope").encrypt(DATA, AAD)).isInstanceOf(NotFoundException.class);
        SealedData sealed = store.key("data").encrypt(DATA, AAD);
        keys.disable("data");
        refresh(store.key("data"));

        assertThatThrownBy(() -> store.key("data").decrypt(sealed, AAD)).isInstanceOf(AccessDeniedException.class)
                .satisfies(e -> assertThat(((AccessDeniedException) e).isRetryable()).isFalse());
        assertThatThrownBy(() -> store.key("data").encrypt(DATA, AAD)).isInstanceOf(AccessDeniedException.class);
        keys.enable("data");
        assertThat(store.key("data").decrypt(sealed, AAD)).isEqualTo(DATA);
    }

    @Test
    void namesArePortableAndPrefixed() {
        KeyStore prefixed = halyx.provider("local").keyStore(KeyStoreConfig.builder().prefix("orders-").build());
        keys.create("orders-pii", KeyPurpose.ENCRYPTION);

        assertThat(prefixed.key("pii").decrypt(prefixed.key("pii").encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
        assertThat(prefixed.keys("")).extracting(KeyMetadata::name).containsExactly("pii");
        assertThat(store.keys("p")).extracting(KeyMetadata::name).containsExactly("p256", "p384");
        assertThatThrownBy(() -> store.key("orders/pii")).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("isn't portable");
        assertThatThrownBy(() -> store.key("x".repeat(64))).isInstanceOf(InvalidArgumentException.class);
        KeyStore nativeNames = halyx.provider("local").keyStore(KeyStoreConfig.builder().nativeNames(true).build());
        keys.create("legacy/key_1", KeyPurpose.ENCRYPTION);
        assertThat(nativeNames.key("legacy/key_1").encrypt(DATA, AAD).keys().get(0).keyId())
                .isEqualTo("memory:/legacy/key_1");
    }

    @Test
    void equalOptionsShareOneKeyAndEqualConfigsOneStore() {
        assertThat(store.key("data")).isSameAs(store.key("data", KeyOptions.defaults()));
        assertThat(store.key("data", KeyOptions.builder().purpose(KeyPurpose.ENCRYPTION).build()))
                .isNotSameAs(store.key("data"));
        assertThat(halyx.provider("local").keyStore()).isSameAs(store);
        assertThat(halyx.provider("local").keyStore(KeyStoreConfig.defaults())).isSameAs(store);
    }

    @Test
    void dataKeyReuseStopsAtItsMessageLimit() {
        KeyStore reusing = halyx.provider("local").keyStore(KeyStoreConfig.builder()
                .dataKeyReuse(Duration.ofMinutes(5), 3).build());
        CryptoKey data = reusing.key("data");

        List<SealedData> sealed = List.of(data.encrypt(DATA, AAD), data.encrypt(DATA, AAD), data.encrypt(DATA, AAD),
                data.encrypt(DATA, AAD));

        assertThat(reusing.statistics().wraps()).isEqualTo(2);
        assertThat(reusing.statistics().reusedDataKeys()).isEqualTo(2);
        assertThat(sealed.get(0).keys()).isEqualTo(sealed.get(2).keys());
        assertThat(sealed.get(0).keys()).isNotEqualTo(sealed.get(3).keys());
        assertThat(sealed.get(0).toBytes()).as("each message has its own salt and nonces")
                .isNotEqualTo(sealed.get(1).toBytes());
        sealed.forEach(s -> assertThat(data.decrypt(s, AAD)).isEqualTo(DATA));
    }

    @Test
    void dataKeyReuseStopsAtItsAge() throws InterruptedException {
        KeyStore reusing = halyx.provider("local").keyStore(KeyStoreConfig.builder()
                .dataKeyReuse(Duration.ofMillis(50), 1000).build());
        CryptoKey data = reusing.key("data");

        data.encrypt(DATA, AAD);
        Thread.sleep(80);
        data.encrypt(DATA, AAD);

        assertThat(reusing.statistics().wraps()).isEqualTo(2);
    }

    @Test
    void theDecryptCacheSavesUnwraps() {
        KeyStore caching = halyx.provider("local").keyStore(KeyStoreConfig.builder()
                .dataKeyCache(Duration.ofMinutes(5), 10).build());
        CryptoKey data = caching.key("data");
        SealedData sealed = data.encrypt(DATA, AAD);

        for (int i = 0; i < 5; i++) {
            assertThat(data.decrypt(sealed, AAD)).isEqualTo(DATA);
        }

        assertThat(caching.statistics().unwraps()).isEqualTo(1);
        assertThat(caching.statistics().unwrapCacheHits()).isEqualTo(4);
    }

    @Test
    void theDecryptCacheEvictsTheLeastRecentlyUsed() {
        KeyStore caching = halyx.provider("local").keyStore(KeyStoreConfig.builder()
                .dataKeyCache(Duration.ofMinutes(5), 2).build());
        CryptoKey data = caching.key("data");
        SealedData a = data.encrypt(DATA, AAD);
        SealedData b = data.encrypt(DATA, AAD);
        SealedData c = data.encrypt(DATA, AAD);

        data.decrypt(a, AAD);
        data.decrypt(b, AAD);
        data.decrypt(c, AAD); // evicts a
        data.decrypt(a, AAD);

        assertThat(caching.statistics().unwraps()).isEqualTo(4);
    }

    @Test
    void theAsyncViewDoesTheSame() {
        CryptoKey data = store.key("data");
        AsyncCryptoKey async = data.async();

        SealedData sealed = async.encrypt(DATA, AAD).join();

        assertThat(async.decrypt(sealed, AAD).join()).isEqualTo(DATA);
        assertThat(store.async().decrypt(sealed, AAD).join()).isEqualTo(DATA);
        assertThat(async.blocking()).isSameAs(data);
        AsyncCryptoKey p256 = store.key("p256").async();
        Signature signature = p256.sign(DATA).join();
        assertThat(p256.verify(DATA, signature).join()).isTrue();
        assertThat(store.key("tags").async().verifyMac(DATA, store.key("tags").mac(DATA)).join()).isTrue();
        assertThatThrownBy(() -> async.decrypt(sealed, new byte[0]).join())
                .hasCauseInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> store.key("nope").async().encrypt(DATA, AAD).join())
                .hasCauseInstanceOf(NotFoundException.class);
    }

    @Test
    void capabilitiesAndConfigurationAreChecked() {
        assertThat(store.capabilities().supports(KeyCapability.MAC)).isTrue();
        assertThat(store.capabilities().supports(KeyCapability.SERVER_SIDE_REWRAP)).isTrue();
        assertThatThrownBy(() -> new KeyStoreConfig.DataKeyReuse(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyStoreConfig.DataKeyReuse(Duration.ofMinutes(1), (1L << 32) + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyStoreConfig.builder().segmentSize(100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(KeyStoreConfig.builder().location(" ").build()).as("blank is unset")
                .isEqualTo(KeyStoreConfig.defaults());
    }

    @Test
    void macsNeedAMacKey() {
        assertThatThrownBy(() -> store.key("data").mac(DATA)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("can't be used for MAC");
    }

    @Test
    void toStringNeverShowsSecrets() {
        CryptoKey data = store.key("data");
        SealedData sealed = data.encrypt(DATA, AAD);

        assertThat(List.of(data.toString(), store.toString(), sealed.toString(), data.generateDataKey().toString()))
                .noneMatch(text -> text.contains("ana@example.com"))
                .noneMatch(text -> text.matches(".*\\[-?\\d+, -?\\d+.*"));
    }

    private static void refresh(CryptoKey key) {
        try {
            var field = DefaultCryptoKey.class.getDeclaredField("metadataReadAtNanos");
            field.setAccessible(true);
            field.setLong(key, System.nanoTime() - DefaultCryptoKey.METADATA_TTL.toNanos() - 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] body(SealedData sealed) {
        byte[] bytes = sealed.toBytes();
        return Arrays.copyOfRange(bytes, sealed.header().length(), bytes.length);
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }
}
