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
package dev.halyx.tck;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.keys.AsyncCryptoKey;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.CryptoKeys;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyCapability;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.KeyStoreStatistics;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import dev.halyx.memory.InMemoryKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for {@link KeyStore} and {@link CryptoKey}. Providers extend it once per store location. Halyx
 * never creates keys, so the provider's test creates them before the kit runs and names them through the hooks; the
 * kit rotates, disables and enables them only through {@link #rotate}, {@link #disable} and {@link #enable}.
 *
 * <p>Sealing for several keys at once is tested against a memory provider the kit starts itself, so every provider
 * proves that its data opens next to, and moves to and from, another key service's.
 */
public abstract class KeyStoreConformanceTest {

    private static final byte[] AAD = "orders/o-1001".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"email\":\"ana@example.com\"}".getBytes(StandardCharsets.UTF_8);
    private static final int SMALL_SEGMENT = KeyStoreConfig.MIN_SEGMENT_SIZE;
    private static final String MEMORY_LOCATION = "tck-backup";

    private Halyx memory;

    protected abstract CloudContext context();

    /** The store configuration under test: its location and provider properties. The kit sets the rest. */
    protected abstract KeyStoreConfig.Builder config();

    /** An enabled encryption key. */
    protected abstract String encryptionKey();

    /** A second enabled encryption key in the same store. */
    protected abstract String otherEncryptionKey();

    /** An encryption key the kit disables and enables again: no other test uses it. */
    protected abstract String disposableKey();

    /**
     * Adds a version to {@code name} and makes it current. The kit rotates {@link #encryptionKey()} once per run, and
     * each signing key once when {@link #rotatesSigningKeys()}.
     */
    protected abstract void rotate(String name);

    /** Disables {@code name}, or every version of it where versions are disabled one by one. */
    protected abstract void disable(String name);

    /** Enables {@code name} again after {@link #disable}. */
    protected abstract void enable(String name);

    /** The signing algorithms this environment has keys for; {@link #signingKey} names one for each. */
    protected Set<SigningAlgorithm> declaredAlgorithms() {
        return EnumSet.allOf(SigningAlgorithm.class);
    }

    /** A signing key for {@code algorithm}, one of {@link #declaredAlgorithms()}. Both RSA algorithms may share one. */
    protected String signingKey(SigningAlgorithm algorithm) {
        throw new UnsupportedOperationException("No signing key for " + algorithm);
    }

    /** An HMAC key, present exactly when the store declares {@link KeyCapability#MAC}. */
    protected Optional<String> macKey() {
        return Optional.empty();
    }

    /** A portable key name that doesn't exist in the store. */
    protected String missingKey() {
        return "tck-missing-key";
    }

    /** Whether signing keys can be rotated: false on AWS KMS, which rotates only symmetric keys. */
    protected boolean rotatesSigningKeys() {
        return true;
    }

    /** How long a rotation or a disable may take to reach every read. Zero for most backends. */
    protected Duration keyStateLag() {
        return Duration.ZERO;
    }

    /** Test names to skip in this environment, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void checkGaps(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
    }

    @AfterEach
    void stopMemory() {
        if (memory != null) {
            memory.close();
            memory = null;
        }
    }

    /** The store under test. Equal configurations share one store, so its statistics are compared as deltas. */
    protected KeyStore store() {
        return context().keyStore(config().build());
    }

    /**
     * A new handle for {@code name}, which reads the key's metadata again: handles keep metadata for minutes, so a test
     * that rotates or disables a key needs one. Override if the driver refuses unknown key properties.
     */
    protected CryptoKey freshKey(KeyStore store, String name, KeyOptions options) {
        KeyOptions.Builder fresh = KeyOptions.builder().property("tck.handle", UUID.randomUUID().toString());
        options.signingAlgorithm().ifPresent(fresh::signingAlgorithm);
        options.version().ifPresent(fresh::version);
        options.purpose().ifPresent(fresh::purpose);
        options.properties().forEach(fresh::property);
        return store.key(name, fresh.build());
    }

    // Round trips

    @Test
    void roundTripsWithAndWithoutAad() {
        CryptoKey key = store().key(encryptionKey());

        assertThat(key.decrypt(key.encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
        assertThat(key.decrypt(key.encrypt(DATA, new byte[0]), new byte[0])).isEqualTo(DATA);
        assertThat(store().decrypt(key.encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
    }

    @Test
    void roundTripsEveryLength() {
        KeyStore small = context().keyStore(config().segmentSize(SMALL_SEGMENT).build());
        CryptoKey key = small.key(encryptionKey());

        for (int length : new int[] {0, 1, SMALL_SEGMENT - 1, SMALL_SEGMENT, SMALL_SEGMENT + 1, 10 * SMALL_SEGMENT,
                10 * SMALL_SEGMENT + 7}) {
            byte[] plaintext = random(length);
            SealedData sealed = key.encrypt(plaintext, AAD);
            assertThat(key.decrypt(sealed, AAD)).as("length %d", length).isEqualTo(plaintext);
            assertThat(store().decrypt(sealed, AAD)).as("segment size travels in the header").isEqualTo(plaintext);
        }
    }

    @Test
    void roundTripsLargeData() {
        CryptoKey key = store().key(encryptionKey());
        byte[] large = random(3 * KeyStoreConfig.DEFAULT_SEGMENT_SIZE * 5 + 11);

        assertThat(key.decrypt(key.encrypt(large, AAD), AAD)).isEqualTo(large);
    }

    @Test
    void storedFormsReadBack() {
        CryptoKey key = store().key(encryptionKey());
        SealedData sealed = key.encrypt(DATA, AAD);

        assertThat(SealedData.parse(sealed.toBytes())).isEqualTo(sealed);
        assertThat(sealed.toText()).startsWith("hlxs1:");
        assertThat(key.decrypt(SealedData.parseText(sealed.toText()), AAD)).isEqualTo(DATA);
        assertThat(sealed.formatVersion()).isEqualTo(1);
        assertThat(key.encrypt(DATA, AAD).toBytes()).as("a fresh data key, salt and nonces per message")
                .isNotEqualTo(sealed.toBytes());
    }

    // AAD and tampering

    @Test
    void aadIsBound() {
        CryptoKey key = store().key(encryptionKey());
        SealedData sealed = key.encrypt(DATA, AAD);

        assertThatThrownBy(() -> key.decrypt(sealed, "orders/o-1002".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> key.decrypt(sealed, new byte[0])).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> key.decrypt(key.encrypt(DATA, new byte[0]), AAD))
                .isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void changedHeaderFieldsAreRefused() {
        Envelope sealed = Envelope.of(store().key(encryptionKey()).encrypt(DATA, AAD));

        refused(sealed.flip(Envelope.MAGIC), InvalidArgumentException.class);
        refused(sealed.set(Envelope.FORMAT_VERSION, 2), InvalidArgumentException.class);
        refused(sealed.set(Envelope.ALGORITHM, 2), InvalidArgumentException.class);
        refused(sealed.set(Envelope.SEGMENT_SIZE, 0x7F), InvalidArgumentException.class); // above 16 MiB
        refused(sealed.flip(Envelope.SEGMENT_SIZE + 3), DataIntegrityException.class); // fails the commitment
        refused(sealed.flip(Envelope.SALT), DataIntegrityException.class);
        refused(sealed.flip(Envelope.NONCE_PREFIX), DataIntegrityException.class);
        refused(sealed.set(Envelope.KEY_COUNT, 0), InvalidArgumentException.class);
        refused(Arrays.copyOf(sealed.encode(), Envelope.KEY_COUNT + 3), DataIntegrityException.class);
    }

    @Test
    void changedKeyEntriesAreRefused() {
        KeyStore store = store();
        Envelope sealed = Envelope.of(store.key(encryptionKey()).encrypt(DATA, AAD));
        Envelope.Entry entry = sealed.entries.get(0);
        KeyMetadata other = store.key(otherEncryptionKey()).metadata();

        refused(sealed.withEntries(new Envelope.Entry(entry.keyId(), entry.version(), entry.algorithm(),
                flipped(entry.wrapped(), entry.wrapped().length / 2))).encode(), DataIntegrityException.class);
        // pointed at another key of the store, at that key's own version: it unwraps under the wrong key
        refused(sealed.withEntries(new Envelope.Entry(other.keyId(), other.currentVersion().orElse(""),
                entry.algorithm(), entry.wrapped())).encode(), DataIntegrityException.class);
        refused(sealed.withEntries(new Envelope.Entry(entry.keyId(), entry.version(), 239, entry.wrapped())).encode(),
                InvalidArgumentException.class); // an unassigned wrap algorithm: skipped, so no usable entry
    }

    @Test
    void changedSegmentsAreRefused() {
        KeyStore small = context().keyStore(config().segmentSize(SMALL_SEGMENT).build());
        Envelope sealed = Envelope.of(small.key(encryptionKey()).encrypt(random(3 * SMALL_SEGMENT + 100), AAD));
        int full = SMALL_SEGMENT + 16;
        byte[] body = sealed.body;

        refused(sealed.withBody(flipped(body, 0)).encode(), DataIntegrityException.class);
        refused(sealed.withBody(flipped(body, full - 1)).encode(), DataIntegrityException.class); // a tag
        refused(sealed.withBody(flipped(body, body.length - 1)).encode(), DataIntegrityException.class);
        byte[] swapped = body.clone();
        System.arraycopy(body, full, swapped, 0, full);
        System.arraycopy(body, 0, swapped, full, full);
        refused(sealed.withBody(swapped).encode(), DataIntegrityException.class);
        refused(sealed.withBody(Arrays.copyOf(body, 3 * full)).encode(), DataIntegrityException.class);
        refused(sealed.withBody(Arrays.copyOf(body, body.length - 1)).encode(), DataIntegrityException.class);
        byte[] appended = Arrays.copyOf(body, body.length + full);
        System.arraycopy(body, 0, appended, body.length, full);
        refused(sealed.withBody(appended).encode(), DataIntegrityException.class);
        refused(sealed.withBody(new byte[0]).encode(), DataIntegrityException.class);
    }

    // Wrong keys

    @Test
    void dataSealedUnderAnotherKeyIsRefused() {
        KeyStore store = store();
        SealedData sealed = store.key(encryptionKey()).encrypt(DATA, AAD);

        assertThatThrownBy(() -> store.key(otherEncryptionKey()).decrypt(sealed, AAD))
                .isInstanceOf(InvalidArgumentException.class);
        assertThat(store.key(encryptionKey()).decrypt(sealed, AAD)).isEqualTo(DATA);
    }

    @Test
    void theStoreOpensDataSealedUnderAnyOfItsKeys() {
        KeyStore store = store();

        assertThat(store.decrypt(store.key(encryptionKey()).encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
        assertThat(store.decrypt(store.key(otherEncryptionKey()).encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
    }

    @Test
    void dataSealedOutsideTheStoreIsRefused() {
        SealedData foreign = memoryKey("backup").encrypt(DATA, AAD);

        assertThatThrownBy(() -> store().decrypt(foreign, AAD)).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> store().decryptDataKey(foreign.keys().get(0)))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> store().key(encryptionKey()).decrypt(foreign, AAD))
                .isInstanceOf(InvalidArgumentException.class);
    }

    // Several keys (D16)

    @Test
    void dataSealedForSeveralKeysOpensWithEachAlone() {
        KeyStore store = store();
        CryptoKey own = store.key(encryptionKey());
        CryptoKey backup = memoryKey("backup");

        SealedData sealed = CryptoKeys.encrypt(DATA, AAD, own, backup);

        assertThat(sealed.keys()).extracting(WrappedKey::keyId)
                .containsExactly(own.metadata().keyId(), backup.metadata().keyId());
        assertThat(own.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(store.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(backup.decrypt(sealed, AAD)).isEqualTo(DATA);
        SealedData reversed = CryptoKeys.encrypt(DATA, AAD, backup, own);
        assertThat(own.decrypt(reversed, AAD)).as("the provider's entry second").isEqualTo(DATA);
        assertThat(store.decrypt(reversed, AAD)).isEqualTo(DATA);
    }

    @Test
    void keysAreAddedAndRemovedWithoutTouchingTheBody() {
        CryptoKey own = store().key(encryptionKey());
        CryptoKey backup = memoryKey("backup");
        SealedData sealed = own.encrypt(DATA, AAD);

        SealedData both = backup.addTo(sealed, store());
        SealedData backupOnly = both.withoutKey(own.metadata().keyId());

        assertThat(both.keys()).extracting(WrappedKey::keyId)
                .containsExactly(own.metadata().keyId(), backup.metadata().keyId());
        assertThat(own.decrypt(both, AAD)).isEqualTo(DATA);
        assertThat(backup.decrypt(both, AAD)).isEqualTo(DATA);
        assertThat(backup.decrypt(backupOnly, AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> own.decrypt(backupOnly, AAD)).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> backupOnly.withoutKey(backup.metadata().keyId()))
                .isInstanceOf(InvalidArgumentException.class);
        assertThat(Envelope.of(both).body).isEqualTo(Envelope.of(sealed).body);
        assertThat(Envelope.of(backupOnly).body).isEqualTo(Envelope.of(sealed).body);
        SealedData back = own.addTo(backupOnly, memoryStore());
        assertThat(own.decrypt(back, AAD)).as("added back from the other store").isEqualTo(DATA);
    }

    @Test
    void sixteenKeysAtMost() {
        CryptoKey own = store().key(encryptionKey());
        CryptoKey[] sixteen = new CryptoKey[16];
        sixteen[0] = own;
        for (int i = 1; i < 16; i++) {
            sixteen[i] = memoryKey("k" + i);
        }
        CryptoKey[] seventeen = Arrays.copyOf(sixteen, 17);
        seventeen[16] = memoryKey("k16");

        SealedData sealed = CryptoKeys.encrypt(DATA, AAD, sixteen);

        assertThat(sealed.keys()).hasSize(16);
        assertThat(own.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(sixteen[15].decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> CryptoKeys.encrypt(DATA, AAD, seventeen)).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> seventeen[16].addTo(sealed, store())).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> CryptoKeys.encrypt(DATA, AAD)).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aKeyIsListedOnce() {
        CryptoKey own = store().key(encryptionKey());

        assertThatThrownBy(() -> CryptoKeys.encrypt(DATA, AAD, own, own)).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> CryptoKeys.encrypt(DATA, AAD, own, store().key(encryptionKey(),
                KeyOptions.builder().purpose(KeyPurpose.ENCRYPTION).build())))
                .as("two handles on one key").isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void entriesWithAnUnknownSchemeAreSkipped() {
        Envelope sealed = Envelope.of(store().key(encryptionKey()).encrypt(DATA, AAD));
        List<Envelope.Entry> entries = new ArrayList<>(sealed.entries);
        entries.add(0, new Envelope.Entry("x-tck:somewhere/else", "", 240, random(48)));

        SealedData withUnknown = SealedData.parse(sealed.withEntries(entries.toArray(Envelope.Entry[]::new)).encode());

        assertThat(withUnknown.keys().get(0).algorithm()).isEmpty();
        assertThat(store().decrypt(withUnknown, AAD)).isEqualTo(DATA);
        assertThat(store().key(encryptionKey()).decrypt(withUnknown, AAD)).isEqualTo(DATA);
    }

    // Key commitment (D17)

    @Test
    void aChangedCommitmentIsRefused() {
        Envelope sealed = Envelope.of(store().key(encryptionKey()).encrypt(DATA, AAD));

        refused(sealed.flip(Envelope.COMMITMENT), DataIntegrityException.class);
        refused(sealed.flip(Envelope.COMMITMENT + 31), DataIntegrityException.class);
    }

    @Test
    void anEntryWrappingAnotherDataKeyIsRefused() {
        CryptoKey key = store().key(encryptionKey());
        Envelope sealed = Envelope.of(key.encrypt(DATA, AAD));
        Envelope.Entry other = Envelope.Entry.of(key.encrypt(DATA, AAD).keys().get(0));

        refused(sealed.withEntries(other).encode(), DataIntegrityException.class);
        try (DataKey dataKey = key.generateDataKey()) {
            refused(sealed.withEntries(Envelope.Entry.of(dataKey.wrapped())).encode(), DataIntegrityException.class);
        }
    }

    @Test
    void anEnvelopeNeverOpensToDifferentPlaintextsUnderDifferentKeys() {
        // A body sealed under one data key, with the key list of another message under a different data key: without
        // the commitment, each key would open its own entry, and the reader couldn't tell which plaintext is real.
        CryptoKey own = store().key(encryptionKey());
        CryptoKey other = store().key(otherEncryptionKey());
        Envelope first = Envelope.of(own.encrypt(DATA, AAD));
        Envelope second = Envelope.of(other.encrypt("{\"email\":\"mallory@example.com\"}"
                .getBytes(StandardCharsets.UTF_8), AAD));

        SealedData forged = SealedData.parse(first.withEntries(first.entries.get(0), second.entries.get(0)).encode());

        assertThat(own.decrypt(forged, AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> other.decrypt(forged, AAD)).isInstanceOf(DataIntegrityException.class);
    }

    // Data keys and streams

    @Test
    void dataKeysForApplicationsWithTheirOwnFormat() {
        KeyStore store = store();
        CryptoKey key = store.key(encryptionKey());
        CryptoKey other = store.key(otherEncryptionKey());

        try (DataKey generated = key.generateDataKey();
             DataKey unwrapped = key.decryptDataKey(generated.wrapped());
             DataKey viaStore = store.decryptDataKey(generated.wrapped())) {
            assertThat(generated.plaintext()).hasSize(32).isEqualTo(unwrapped.plaintext())
                    .isEqualTo(viaStore.plaintext());
            assertThat(generated.wrapped().keyId()).isEqualTo(key.metadata().keyId());
            WrappedKey alsoWrapped = other.wrap(generated);
            try (DataKey viaOther = other.decryptDataKey(alsoWrapped)) {
                assertThat(viaOther.plaintext()).isEqualTo(generated.plaintext());
            }
            assertThat(generated.toString()).doesNotContain(Arrays.toString(generated.plaintext()));
            assertThatThrownBy(() -> other.decryptDataKey(generated.wrapped()))
                    .isInstanceOf(InvalidArgumentException.class);
        }
        try (DataKey first = key.generateDataKey(); DataKey second = key.generateDataKey()) {
            assertThat(first.plaintext()).isNotEqualTo(second.plaintext());
        }
    }

    @Test
    void streamsSealAndOpenOneSegmentAtATime() throws IOException {
        KeyStore small = context().keyStore(config().segmentSize(SMALL_SEGMENT).build());
        CryptoKey key = small.key(encryptionKey());
        for (int length : new int[] {0, 1, SMALL_SEGMENT, 5 * SMALL_SEGMENT + 3}) {
            byte[] plaintext = random(length);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (OutputStream out = key.encryptingStream(sink, AAD)) {
                for (int i = 0; i < length; i += 1000) {
                    out.write(plaintext, i, Math.min(1000, length - i));
                }
            }

            assertThat(readAll(small.decryptingStream(new ByteArrayInputStream(sink.toByteArray()), AAD)))
                    .as("length %d", length).isEqualTo(plaintext);
            assertThat(key.decrypt(SealedData.parse(sink.toByteArray()), AAD)).isEqualTo(plaintext);
            byte[] sealedInOne = key.encrypt(plaintext, AAD).toBytes();
            assertThat(readAll(store().decryptingStream(new ByteArrayInputStream(sealedInOne), AAD)))
                    .isEqualTo(plaintext);
        }
    }

    @Test
    void anUnclosedStreamFailsToOpen() throws IOException {
        KeyStore small = context().keyStore(config().segmentSize(SMALL_SEGMENT).build());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream out = small.key(encryptionKey()).encryptingStream(sink, AAD);
        out.write(random(3 * SMALL_SEGMENT));
        out.flush();

        assertThatThrownBy(() -> small.decrypt(SealedData.parse(sink.toByteArray()), AAD))
                .isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> readAll(small.decryptingStream(new ByteArrayInputStream(sink.toByteArray()), AAD)))
                .isInstanceOf(DataIntegrityException.class);
        out.close();
    }

    @Test
    void streamsSealForSeveralKeys() throws IOException {
        CryptoKey own = store().key(encryptionKey());
        CryptoKey backup = memoryKey("backup");
        byte[] plaintext = random(200_000);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = CryptoKeys.encryptingStream(sink, AAD, own, backup)) {
            out.write(plaintext);
        }

        assertThat(readAll(store().decryptingStream(new ByteArrayInputStream(sink.toByteArray()), AAD)))
                .isEqualTo(plaintext);
        assertThat(backup.decrypt(SealedData.parse(sink.toByteArray()), AAD)).isEqualTo(plaintext);
    }

    // Rewrap

    @Test
    void rewrapAfterRotationChangesOnlyTheHeader() {
        KeyStore store = store();
        CryptoKey key = store.key(encryptionKey());
        SealedData sealed = key.encrypt(random(100_000), AAD);
        Optional<String> before = key.metadata().currentVersion();

        rotate(encryptionKey());
        KeyMetadata rotated = eventually(() -> {
            KeyMetadata read = freshKey(store, encryptionKey(), KeyOptions.defaults()).metadata();
            assertThat(read.currentVersion()).as("the current version after rotation")
                    .satisfiesAnyOf(v -> assertThat(v).isEmpty(), v -> assertThat(v).isNotEqualTo(before));
            return read;
        });
        CryptoKey current = freshKey(store, encryptionKey(), KeyOptions.defaults());
        SealedData rewrapped = current.rewrap(sealed);

        assertThat(rewrapped.keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo(rotated.keyId());
            assertThat(entry.version()).isEqualTo(rotated.currentVersion());
        });
        assertThat(Envelope.of(rewrapped).body).isEqualTo(Envelope.of(sealed).body);
        assertThat(current.decrypt(rewrapped, AAD)).isEqualTo(key.decrypt(sealed, AAD));
        assertThat(current.decrypt(sealed, AAD)).as("older versions keep opening").isEqualTo(key.decrypt(sealed, AAD));
        assertThat(current.decrypt(current.encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
    }

    @Test
    void rewrapMovesDataBetweenKeysOfTheStore() {
        KeyStore store = store();
        CryptoKey from = store.key(encryptionKey());
        CryptoKey to = store.key(otherEncryptionKey());
        SealedData sealed = from.encrypt(DATA, AAD);

        SealedData moved = to.rewrap(sealed);

        assertThat(moved.keys()).extracting(WrappedKey::keyId).containsExactly(to.metadata().keyId());
        assertThat(to.decrypt(moved, AAD)).isEqualTo(DATA);
        assertThat(Envelope.of(moved).body).isEqualTo(Envelope.of(sealed).body);
        assertThatThrownBy(() -> from.decrypt(moved, AAD)).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void rewrapMovesDataBetweenStores() {
        CryptoKey own = store().key(encryptionKey());
        CryptoKey backup = memoryKey("backup");

        SealedData in = own.rewrap(backup.encrypt(DATA, AAD), memoryStore());
        SealedData out = backup.rewrap(own.encrypt(DATA, AAD), store());

        assertThat(in.keys()).extracting(WrappedKey::keyId).containsExactly(own.metadata().keyId());
        assertThat(own.decrypt(in, AAD)).isEqualTo(DATA);
        assertThat(out.keys()).extracting(WrappedKey::keyId).containsExactly(backup.metadata().keyId());
        assertThat(backup.decrypt(out, AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> own.rewrap(backup.encrypt(DATA, AAD))).as("not from this store")
                .isInstanceOf(InvalidArgumentException.class);
    }

    // Signing

    @ParameterizedTest
    @EnumSource(SigningAlgorithm.class)
    void signsAndVerifies(SigningAlgorithm algorithm) {
        CryptoKey key = signer(algorithm);

        Signature signature = key.sign(DATA);

        assertThat(signature.algorithm()).isEqualTo(algorithm);
        assertThat(key.verify(DATA, signature)).isTrue();
        assertThat(key.verify(DATA, Signature.parse(signature.toBytes()))).isTrue();
        assertThat(key.verify("other".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(key.verify(DATA, new Signature(flipped(signature.bytes(), signature.bytes().length - 1), algorithm,
                signature.keyVersion()))).isFalse();
        assertThat(key.capabilities().supports(KeyCapability.SIGN)).isTrue();
        assertThat(key.metadata().purposes()).contains(KeyPurpose.SIGNING);
        assertThat(key.metadata().signingAlgorithms()).contains(algorithm);
    }

    @ParameterizedTest
    @EnumSource(SigningAlgorithm.class)
    void signaturesAreWhatTheAlgorithmDefines(SigningAlgorithm algorithm) throws GeneralSecurityException {
        CryptoKey key = signer(algorithm);

        Signature fromData = key.sign(DATA);
        Signature fromDigest = key.signDigest(MessageDigest.getInstance(algorithm.digestAlgorithm()).digest(DATA));

        for (Signature signature : List.of(fromData, fromDigest)) {
            PublicKey publicKey = key.publicKeys().get(signature.keyVersion().orElse(""));
            assertThat(publicKey).as("publicKeys() has the signing version").isNotNull();
            assertThat(jdkVerify(algorithm, publicKey, DATA, signature.bytes()))
                    .as("verified by the JDK alone").isTrue();
        }
        assertThat(key.verify(DATA, fromDigest)).isTrue();
        assertThat(key.currentPublicKey().getEncoded()).isNotEmpty();
        assertThatThrownBy(() -> key.signDigest(new byte[algorithm.digestLength() - 1]))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(SigningAlgorithm.class)
    void signaturesFromAPreviousVersionStillVerify(SigningAlgorithm algorithm) {
        assumeTrue(rotatesSigningKeys(), "signing keys don't rotate here");
        KeyStore store = store();
        KeyOptions options = KeyOptions.builder().signingAlgorithm(algorithm).build();
        CryptoKey key = signer(algorithm);
        Signature before = key.sign(DATA);

        rotate(signingKey(algorithm));
        Signature after = eventually(() -> {
            Signature signed = freshKey(store, signingKey(algorithm), options).sign(DATA);
            assertThat(signed.keyVersion()).isNotEqualTo(before.keyVersion());
            return signed;
        });
        CryptoKey current = freshKey(store, signingKey(algorithm), options);

        assertThat(current.verify(DATA, before)).isTrue();
        assertThat(current.verify(DATA, after)).isTrue();
        assertThat(current.publicKeys()).containsKeys(before.keyVersion().orElseThrow(),
                after.keyVersion().orElseThrow());
        assertThat(current.verify(DATA, new Signature(before.bytes(), algorithm, after.keyVersion())))
                .as("the other version's public key").isFalse();
    }

    @ParameterizedTest
    @EnumSource(SigningAlgorithm.class)
    void aSigningKeyRefusesAnotherAlgorithm(SigningAlgorithm algorithm) {
        assumeTrue(declaredAlgorithms().contains(algorithm), "no " + algorithm + " key here");
        SigningAlgorithm otherFamily = switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> SigningAlgorithm.ECDSA_P256_SHA256;
            case ECDSA_P256_SHA256, ECDSA_P384_SHA384 -> SigningAlgorithm.RSA_PKCS1_SHA256;
        };

        assertThatThrownBy(() -> store().key(signingKey(algorithm),
                KeyOptions.builder().signingAlgorithm(otherFamily).build()).sign(DATA))
                .isInstanceOf(InvalidArgumentException.class);
    }

    // Purposes and failures

    @Test
    void purposesAreChecked() {
        CryptoKey encryption = store().key(encryptionKey(), KeyOptions.builder().purpose(KeyPurpose.ENCRYPTION).build());

        assertThat(encryption.metadata().purposes()).contains(KeyPurpose.ENCRYPTION);
        assertThatThrownBy(() -> encryption.sign(DATA)).isInstanceOf(InvalidArgumentException.class);
        // A store that makes no MACs says so before any key is consulted: the capability, not the purpose.
        assertThatThrownBy(() -> store().key(encryptionKey(), KeyOptions.builder().purpose(KeyPurpose.MAC).build())
                .mac(DATA)).isInstanceOf(store().capabilities().supports(KeyCapability.MAC)
                ? InvalidArgumentException.class : UnsupportedFeatureException.class);
        declaredAlgorithms().stream().findFirst().ifPresent(algorithm -> {
            CryptoKey signing = store().key(signingKey(algorithm), KeyOptions.builder().signingAlgorithm(algorithm)
                    .purpose(KeyPurpose.SIGNING).build());
            assertThatThrownBy(() -> signing.encrypt(DATA, AAD)).isInstanceOf(InvalidArgumentException.class);
        });
    }

    @Test
    void aMissingKeyIsNotFound() {
        CryptoKey missing = store().key(missingKey());

        assertThatThrownBy(missing::metadata).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> missing.encrypt(DATA, AAD)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(missing::generateDataKey).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDisabledKeyRefusesEncryption() {
        KeyStore store = store();
        store.key(disposableKey()).encrypt(DATA, AAD);
        disable(disposableKey());
        try {
            eventually(() -> {
                assertThatThrownBy(() -> freshKey(store, disposableKey(), KeyOptions.defaults()).encrypt(DATA, AAD))
                        .isInstanceOf(AccessDeniedException.class);
                return null;
            });
        } finally {
            enable(disposableKey());
        }
        eventually(() -> freshKey(store, disposableKey(), KeyOptions.defaults()).encrypt(DATA, AAD));
    }

    @Test
    void aDisabledKeyRefusesDecryption() {
        KeyStore store = store();
        SealedData sealed = store.key(disposableKey()).encrypt(DATA, AAD);
        disable(disposableKey());
        try {
            eventually(() -> {
                assertThatThrownBy(() -> store.decrypt(sealed, AAD)).isInstanceOf(AccessDeniedException.class)
                        .satisfies(e -> assertThat(((AccessDeniedException) e).isRetryable()).isFalse());
                return null;
            });
            assertThat(freshKey(store, disposableKey(), KeyOptions.defaults()).metadata().enabled()).isFalse();
        } finally {
            enable(disposableKey());
        }
        assertThat(eventually(() -> store.decrypt(sealed, AAD))).isEqualTo(DATA);
    }

    // Caches and statistics

    @Test
    void statisticsCountKeyServiceCalls() {
        KeyStore store = store();
        CryptoKey key = store.key(encryptionKey());
        key.metadata();
        long wraps = store.statistics().wraps();
        long unwraps = store.statistics().unwraps();

        SealedData sealed = key.encrypt(DATA, AAD);
        key.decrypt(sealed, AAD);
        store.decrypt(sealed, AAD);

        assertThat(store.statistics().wraps() - wraps).isEqualTo(1);
        assertThat(store.statistics().unwraps() - unwraps).isEqualTo(2);
    }

    @Test
    void dataKeyReuseStopsAtItsMessageLimit() {
        KeyStore reusing = context().keyStore(config().dataKeyReuse(Duration.ofMinutes(5), 3).build());
        CryptoKey key = reusing.key(otherEncryptionKey());
        Counts before = Counts.of(reusing.statistics());

        List<SealedData> sealed = List.of(key.encrypt(DATA, AAD), key.encrypt(DATA, AAD), key.encrypt(DATA, AAD),
                key.encrypt(DATA, AAD));

        Counts after = Counts.of(reusing.statistics());
        assertThat(after.wraps - before.wraps).isEqualTo(2);
        assertThat(after.reused - before.reused).isEqualTo(2);
        assertThat(sealed.get(0).keys()).isEqualTo(sealed.get(2).keys());
        assertThat(sealed.get(0).keys()).isNotEqualTo(sealed.get(3).keys());
        assertThat(sealed.get(0).toBytes()).as("each message has its own salt and nonces")
                .isNotEqualTo(sealed.get(1).toBytes());
        sealed.forEach(s -> assertThat(store().decrypt(s, AAD)).isEqualTo(DATA));
    }

    @Test
    void dataKeyReuseStopsAtItsAge() throws InterruptedException {
        KeyStore reusing = context().keyStore(config().dataKeyReuse(Duration.ofMillis(200), 1000).build());
        CryptoKey key = reusing.key(otherEncryptionKey());
        key.metadata();
        long wraps = reusing.statistics().wraps();

        key.encrypt(DATA, AAD);
        Thread.sleep(300);
        key.encrypt(DATA, AAD);

        assertThat(reusing.statistics().wraps() - wraps).isEqualTo(2);
    }

    @Test
    void theDecryptCacheSavesUnwraps() {
        KeyStore caching = context().keyStore(config().dataKeyCache(Duration.ofMinutes(5), 10).build());
        CryptoKey key = caching.key(encryptionKey());
        SealedData sealed = key.encrypt(DATA, AAD);
        Counts before = Counts.of(caching.statistics());

        for (int i = 0; i < 5; i++) {
            assertThat(key.decrypt(sealed, AAD)).isEqualTo(DATA);
        }
        assertThat(caching.decrypt(sealed, AAD)).isEqualTo(DATA);

        Counts after = Counts.of(caching.statistics());
        assertThat(after.unwraps - before.unwraps).isEqualTo(1);
        assertThat(after.cacheHits - before.cacheHits).isEqualTo(5);
        assertThatThrownBy(() -> key.decrypt(sealed, new byte[0])).as("a cached key still checks the AAD")
                .isInstanceOf(DataIntegrityException.class);
    }

    // Names

    @Test
    void namesArePortableAndPrefixed() {
        String name = encryptionKey();
        String prefix = name.substring(0, (name.length() + 1) / 2);
        KeyStore prefixed = context().keyStore(config().prefix(prefix).build());
        CryptoKey key = prefixed.key(name.substring(prefix.length()));

        assertThat(store().decrypt(key.encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
        assertThat(key.metadata().keyId()).isEqualTo(store().key(name).metadata().keyId());
        assertThat(prefixed.keys("")).extracting(KeyMetadata::name).contains(name.substring(prefix.length()));
        assertThat(store().keys(name)).extracting(KeyMetadata::name).contains(name);
        assertThat(store().keys("")).extracting(KeyMetadata::name).contains(name, otherEncryptionKey());
        assertThatThrownBy(() -> store().key("orders/pii")).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> store().key("x".repeat(64))).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> store().key(" ")).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void nativeNamesReachTheSameKey() {
        KeyMetadata metadata = store().key(encryptionKey()).metadata();
        KeyStore nativeNames = context().keyStore(config().nativeNames(true).build());

        SealedData sealed = nativeNames.key(metadata.nativeId()).encrypt(DATA, AAD);

        assertThat(sealed.keys()).extracting(WrappedKey::keyId).containsExactly(metadata.keyId());
        assertThat(store().key(encryptionKey()).decrypt(sealed, AAD)).isEqualTo(DATA);
    }

    @Test
    void metadataDescribesTheKey() {
        CryptoKey key = store().key(encryptionKey());
        KeyMetadata metadata = key.metadata();

        assertThat(metadata.name()).isEqualTo(encryptionKey());
        assertThat(metadata.keyId()).matches("[a-z][a-z0-9-]*:.+");
        assertThat(metadata.nativeId()).isNotBlank();
        assertThat(metadata.purposes()).contains(KeyPurpose.ENCRYPTION);
        assertThat(metadata.enabled()).isTrue();
        assertThat(metadata.keyType()).isNotBlank();
        metadata.currentVersion().ifPresent(version -> assertThat(metadata.versions()).contains(version));
        assertThat(key.encrypt(DATA, AAD).keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo(metadata.keyId());
            assertThat(entry.algorithm()).isPresent();
            assertThat(entry.scheme()).isEqualTo(metadata.keyId().substring(0, metadata.keyId().indexOf(':')));
        });
        assertThat(key.name()).isEqualTo(encryptionKey());
        assertThat(key.store()).isSameAs(store());
        assertThat(key.provider()).isEqualTo(store().provider());
    }

    @Test
    void equalOptionsShareOneKey() {
        assertThat(store().key(encryptionKey())).isSameAs(store().key(encryptionKey(), KeyOptions.defaults()));
        assertThat(context().keyStore(config().build())).isSameAs(store());
    }

    @Test
    void pingReachesTheKeyService() {
        store().ping();
        store().async().ping().join();
    }

    @Test
    void toStringNeverShowsSecrets() {
        CryptoKey key = store().key(encryptionKey());
        SealedData sealed = key.encrypt(DATA, AAD);

        try (DataKey dataKey = key.generateDataKey()) {
            assertThat(List.of(key.toString(), store().toString(), sealed.toString(), dataKey.toString(),
                    sealed.keys().get(0).toString()))
                    .noneMatch(text -> text.contains("ana@example.com"))
                    .noneMatch(text -> text.contains("orders/o-1001"))
                    .noneMatch(text -> text.matches(".*\\[-?\\d+, -?\\d+.*"));
        }
        assertThatThrownBy(() -> key.decrypt(sealed, "secret-aad".getBytes(StandardCharsets.UTF_8)))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-aad").doesNotContain("ana@"));
    }

    // MAC

    @Test
    void macsAreMadeAndVerifiedByTheKeyService() {
        boolean declared = store().capabilities().supports(KeyCapability.MAC);
        assertThat(macKey().isPresent()).as("a MAC key exactly when the store declares MAC").isEqualTo(declared);
        assumeTrue(declared, "the store doesn't declare MAC");
        CryptoKey key = store().key(macKey().orElseThrow());

        byte[] mac = key.mac(DATA);

        assertThat(mac).hasSize(32).isEqualTo(key.mac(DATA));
        assertThat(key.verifyMac(DATA, mac)).isTrue();
        assertThat(key.verifyMac("other".getBytes(StandardCharsets.UTF_8), mac)).isFalse();
        assertThat(key.verifyMac(DATA, flipped(mac, 0))).isFalse();
        assertThat(key.capabilities().supports(KeyCapability.MAC)).isTrue();
        assertThat(key.metadata().purposes()).contains(KeyPurpose.MAC);
        assertThatThrownBy(() -> key.encrypt(DATA, AAD)).isInstanceOf(InvalidArgumentException.class);
    }

    // Async

    @Test
    void theAsyncViewDoesTheSame() {
        KeyStore store = store();
        CryptoKey key = store.key(encryptionKey());
        AsyncCryptoKey async = key.async();

        SealedData sealed = async.encrypt(DATA, AAD).join();

        assertThat(async.decrypt(sealed, AAD).join()).isEqualTo(DATA);
        assertThat(store.async().decrypt(sealed, AAD).join()).isEqualTo(DATA);
        assertThat(async.metadata().join().keyId()).isEqualTo(key.metadata().keyId());
        assertThat(async.name()).isEqualTo(encryptionKey());
        assertThat(async.blocking()).isSameAs(key);
        assertThat(store.async().keys(encryptionKey()).join()).extracting(KeyMetadata::name).contains(encryptionKey());
        try (DataKey generated = async.generateDataKey().join();
             DataKey unwrapped = store.async().decryptDataKey(generated.wrapped()).join()) {
            assertThat(unwrapped.plaintext()).isEqualTo(generated.plaintext());
        }
        assertThatThrownBy(() -> async.decrypt(sealed, new byte[0]).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> store.async().decrypt(memoryKey("backup").encrypt(DATA, AAD), AAD).join())
                .hasCauseInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> store.key(missingKey()).async().encrypt(DATA, AAD).join())
                .hasCauseInstanceOf(NotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(SigningAlgorithm.class)
    void theAsyncViewSigns(SigningAlgorithm algorithm) throws GeneralSecurityException {
        AsyncCryptoKey async = signer(algorithm).async();

        Signature signature = async.sign(DATA).join();
        Signature fromDigest = async.signDigest(MessageDigest.getInstance(algorithm.digestAlgorithm()).digest(DATA))
                .join();

        assertThat(async.verify(DATA, signature).join()).isTrue();
        assertThat(async.verify(DATA, fromDigest).join()).isTrue();
        assertThat(async.verify("other".getBytes(StandardCharsets.UTF_8), signature).join()).isFalse();
    }

    @Test
    void theAsyncViewMacs() {
        assumeTrue(macKey().isPresent(), "no MAC key here");
        AsyncCryptoKey async = store().key(macKey().orElseThrow()).async();

        byte[] mac = async.mac(DATA).join();

        assertThat(async.verifyMac(DATA, mac).join()).isTrue();
        assertThat(async.verifyMac(DATA, flipped(mac, 0)).join()).isFalse();
    }

    // Helpers

    private CryptoKey signer(SigningAlgorithm algorithm) {
        assumeTrue(declaredAlgorithms().contains(algorithm), "no " + algorithm + " key here");
        return store().key(signingKey(algorithm), KeyOptions.builder().signingAlgorithm(algorithm).build());
    }

    /** The kit's own memory key store, standing in for another provider or cloud. */
    private KeyStore memoryStore() {
        if (memory == null) {
            memory = Halyx.builder().register("tck-memory", ProviderConfig.of("memory")).build();
        }
        return memory.provider("tck-memory").keyStore(KeyStoreConfig.builder().location(MEMORY_LOCATION).build());
    }

    private CryptoKey memoryKey(String name) {
        KeyStore store = memoryStore();
        try {
            store.unwrap(InMemoryKeys.class).create(name, KeyPurpose.ENCRYPTION);
        } catch (IllegalArgumentException e) {
            // already created in this test
        }
        return store.key(name);
    }

    /** Asserts that {@code sealed} is refused with {@code type}, whole and as a stream. */
    private void refused(byte[] sealed, Class<? extends Exception> type) {
        assertThatThrownBy(() -> store().decrypt(SealedData.parse(sealed), AAD)).as("decrypt").isInstanceOf(type);
        assertThatThrownBy(() -> readAll(store().decryptingStream(new ByteArrayInputStream(sealed), AAD)))
                .as("decryptingStream").isInstanceOf(type);
    }

    /** {@code action}'s result once it passes, retrying for up to {@link #keyStateLag()}. */
    private <T> T eventually(Supplier<T> action) {
        long deadline = System.nanoTime() + keyStateLag().toNanos();
        while (true) {
            try {
                return action.get();
            } catch (AssertionError | RuntimeException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean jdkVerify(SigningAlgorithm algorithm, PublicKey key, byte[] data, byte[] signature)
            throws GeneralSecurityException {
        java.security.Signature verifier = switch (algorithm) {
            case RSA_PSS_SHA256 -> {
                java.security.Signature pss = java.security.Signature.getInstance("RSASSA-PSS");
                pss.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                yield pss;
            }
            case RSA_PKCS1_SHA256 -> java.security.Signature.getInstance("SHA256withRSA");
            case ECDSA_P256_SHA256 -> java.security.Signature.getInstance("SHA256withECDSA");
            case ECDSA_P384_SHA384 -> java.security.Signature.getInstance("SHA384withECDSA");
        };
        verifier.initVerify(key);
        verifier.update(data);
        return verifier.verify(signature);
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] flipped(byte[] bytes, int index) {
        byte[] copy = bytes.clone();
        copy[index] ^= 1;
        return copy;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }

    private record Counts(long wraps, long unwraps, long cacheHits, long reused) {
        static Counts of(KeyStoreStatistics statistics) {
            return new Counts(statistics.wraps(), statistics.unwraps(), statistics.unwrapCacheHits(),
                    statistics.reusedDataKeys());
        }
    }

    /**
     * An HLXS v1 envelope taken apart as {@code docs/api/keys-format.md} lays it out, written from the spec rather than
     * Halyx's own code, so the kit can change one field at a time.
     */
    private static final class Envelope {
        static final int MAGIC = 0;
        static final int FORMAT_VERSION = 4;
        static final int ALGORITHM = 5;
        static final int SEGMENT_SIZE = 6;
        static final int SALT = 10;
        static final int NONCE_PREFIX = 26;
        static final int COMMITMENT = 33;
        static final int KEY_COUNT = 65;

        record Entry(String keyId, String version, int algorithm, byte[] wrapped) {
            static Entry of(WrappedKey key) {
                return new Entry(key.keyId(), key.version().orElse(""),
                        key.algorithm().map(WrapAlgorithm::id).orElseThrow(), key.bytes());
            }
        }

        final byte[] fixed;
        final List<Entry> entries;
        final byte[] body;

        private Envelope(byte[] fixed, List<Entry> entries, byte[] body) {
            this.fixed = fixed;
            this.entries = List.copyOf(entries);
            this.body = body;
        }

        static Envelope of(SealedData sealed) {
            ByteBuffer in = ByteBuffer.wrap(sealed.toBytes());
            byte[] fixed = new byte[KEY_COUNT];
            in.get(fixed);
            int count = in.get() & 0xFF;
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String keyId = new String(field(in), StandardCharsets.UTF_8);
                String version = new String(field(in), StandardCharsets.UTF_8);
                int algorithm = in.get() & 0xFF;
                entries.add(new Entry(keyId, version, algorithm, field(in)));
            }
            byte[] body = new byte[in.remaining()];
            in.get(body);
            return new Envelope(fixed, entries, body);
        }

        private static byte[] field(ByteBuffer in) {
            byte[] value = new byte[in.getShort() & 0xFFFF];
            in.get(value);
            return value;
        }

        Envelope withEntries(Entry... replaced) {
            return new Envelope(fixed, List.of(replaced), body);
        }

        Envelope withBody(byte[] replaced) {
            return new Envelope(fixed, entries, replaced);
        }

        /** The encoded envelope with bit 0 of the byte at {@code offset} flipped. */
        byte[] flip(int offset) {
            return flipped(encode(), offset);
        }

        /** The encoded envelope with the byte at {@code offset} set to {@code value}. */
        byte[] set(int offset, int value) {
            byte[] bytes = encode();
            bytes[offset] = (byte) value;
            return bytes;
        }

        byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(fixed);
            out.write(entries.size());
            for (Entry entry : entries) {
                writeField(out, entry.keyId().getBytes(StandardCharsets.UTF_8));
                writeField(out, entry.version().getBytes(StandardCharsets.UTF_8));
                out.write(entry.algorithm());
                writeField(out, entry.wrapped());
            }
            out.writeBytes(body);
            return out.toByteArray();
        }

        private static void writeField(ByteArrayOutputStream out, byte[] value) {
            out.write(value.length >>> 8);
            out.write(value.length);
            out.writeBytes(value);
        }
    }
}
