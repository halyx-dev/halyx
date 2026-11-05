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

import dev.halyx.capability.Capabilities;
import dev.halyx.capability.Capability;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;

import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** One key of a {@link DefaultKeyStore}: envelope encryption, hashing and local verification over its driver. */
final class DefaultCryptoKey implements CryptoKey {

    /** How long metadata is trusted before it's read again, so a rotation is seen without a restart. */
    static final Duration METADATA_TTL = Duration.ofMinutes(5);

    private final DefaultKeyStore store;
    private final String name;
    private final String nativeName;
    private final KeyOptions options;
    private final AsyncCryptoKey async = new Async(InterceptorChain.blocking());

    private volatile KeyMetadata metadata;
    private volatile long metadataReadAtNanos;
    /** The data key in use while reuse is on; guarded by {@code this}. */
    private ReusedKey reused;

    private static final class ReusedKey {
        final byte[] dek;
        final WrappedKey wrapped;
        final long createdAtNanos = System.nanoTime();
        long messages;

        ReusedKey(byte[] dek, WrappedKey wrapped) {
            this.dek = dek;
            this.wrapped = wrapped;
        }
    }

    /** A data key and its wrapped form for one encryption; {@code dek} is a copy the caller zeroes. */
    private record Lease(byte[] dek, WrappedKey wrapped) {
    }

    DefaultCryptoKey(DefaultKeyStore store, String name, String nativeName, KeyOptions options) {
        this.store = store;
        this.name = name;
        this.nativeName = nativeName;
        this.options = options;
    }

    @Override
    public KeyStore store() {
        return store;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String provider() {
        return store.provider();
    }

    // --- metadata and checks ----------------------------------------------------------------------------------

    @Override
    public KeyMetadata metadata() {
        KeyMetadata current = metadata;
        if (current != null && fresh()) {
            return current;
        }
        return accept(store.call("metadata", nativeName, () -> store.driver().metadata(nativeName)));
    }

    private CompletableFuture<KeyMetadata> metadataAsync(Executor blocking) {
        KeyMetadata current = metadata;
        if (current != null && fresh()) {
            return CompletableFuture.completedFuture(current);
        }
        return store.callAsync("metadata", nativeName, () -> store.driver().metadataAsync(nativeName, blocking),
                blocking).thenApply(this::accept);
    }

    private boolean fresh() {
        return System.nanoTime() - metadataReadAtNanos < METADATA_TTL.toNanos();
    }

    /** Checks freshly read metadata against the options, then keeps it. */
    private KeyMetadata accept(KeyMetadata read) {
        store.stats().metadataRead();
        KeyMetadata named = read.withName(name);
        options.purpose().ifPresent(purpose -> {
            if (!named.purposes().contains(purpose)) {
                throw new InvalidArgumentException("Key " + name + " can't be used for " + purpose + ": it's for "
                        + named.purposes() + " (" + named.keyType() + ")", null, provider(), null);
            }
        });
        options.signingAlgorithm().ifPresent(algorithm -> {
            if (!named.signingAlgorithms().contains(algorithm)) {
                throw new InvalidArgumentException("Key " + name + " (" + named.keyType() + ") can't sign with "
                        + algorithm + "; it signs with " + named.signingAlgorithms(), null, provider(), null);
            }
        });
        options.version().ifPresent(version -> {
            if (!named.versions().isEmpty() && !named.versions().contains(version)) {
                throw new InvalidArgumentException("Key " + name + " has no enabled version " + version, null,
                        provider(), null);
            }
        });
        metadata = named;
        metadataReadAtNanos = System.nanoTime();
        return named;
    }

    private Set<KeyPurpose> purposes(KeyMetadata metadata) {
        return options.purpose().map(Set::of).orElse(metadata.purposes());
    }

    private void require(KeyMetadata metadata, KeyPurpose purpose) {
        if (!purposes(metadata).contains(purpose)) {
            throw new InvalidArgumentException("Key " + name + " can't be used for " + purpose + ": it's for "
                    + purposes(metadata) + " (" + metadata.keyType() + ")", null, provider(), null);
        }
    }

    private KeyMetadata require(KeyPurpose purpose) {
        KeyMetadata current = metadata();
        require(current, purpose);
        return current;
    }

    private SigningAlgorithm algorithm(KeyMetadata metadata) {
        if (options.signingAlgorithm().isPresent()) {
            return options.signingAlgorithm().get();
        }
        Set<SigningAlgorithm> algorithms = metadata.signingAlgorithms();
        if (algorithms.size() == 1) {
            return algorithms.iterator().next();
        }
        throw new InvalidArgumentException("Key " + name + " (" + metadata.keyType() + ") signs with " + algorithms
                + ": choose one with KeyOptions.signingAlgorithm", null, provider(), null);
    }

    private Optional<String> signingVersion(KeyMetadata metadata) {
        return options.version().or(metadata::currentVersion);
    }

    // --- encryption -------------------------------------------------------------------------------------------

    @Override
    public SealedData encrypt(byte[] plaintext, byte[] aad) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(aad, "aad");
        require(KeyPurpose.ENCRYPTION);
        Lease lease = lease();
        try {
            return seal(lease, plaintext, aad);
        } finally {
            Arrays.fill(lease.dek(), (byte) 0);
        }
    }

    private SealedData seal(Lease lease, byte[] plaintext, byte[] aad) {
        return SealedData.of(Hlxs.seal(lease.dek(), List.of(lease.wrapped()), plaintext, aad,
                store.config().segmentSize(), Hlxs.randomSalt(), Hlxs.randomNoncePrefix()));
    }

    /** A data key for one encryption: a reused one within the limits, else a fresh one wrapped now. */
    private Lease lease() {
        Optional<KeyStoreConfig.DataKeyReuse> reuse = store.config().dataKeyReuse();
        if (reuse.isEmpty()) {
            byte[] dek = Hlxs.newDataKey();
            try {
                return new Lease(dek.clone(), wrapNow(dek));
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        }
        synchronized (this) {
            if (reused != null && System.nanoTime() - reused.createdAtNanos < reuse.get().maxAge().toNanos()
                    && reused.messages < reuse.get().maxMessages()) {
                reused.messages++;
                store.stats().reusedDataKey();
                return new Lease(reused.dek.clone(), reused.wrapped);
            }
            if (reused != null) {
                Arrays.fill(reused.dek, (byte) 0);
                reused = null;
            }
            byte[] dek = Hlxs.newDataKey();
            WrappedKey wrapped = wrapNow(dek);
            reused = new ReusedKey(dek, wrapped);
            reused.messages = 1;
            return new Lease(dek.clone(), wrapped);
        }
    }

    /** Zeroes the reused data key, when the store closes. */
    synchronized void clearReused() {
        if (reused != null) {
            Arrays.fill(reused.dek, (byte) 0);
            reused = null;
        }
    }

    private WrappedKey wrapNow(byte[] dek) {
        WrappedKey wrapped = store.call("wrap", nativeName,
                () -> store.driver().wrapKey(nativeName, options.version(), dek));
        store.stats().wrapped();
        return wrapped;
    }

    @Override
    public byte[] decrypt(SealedData sealed, byte[] aad) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(aad, "aad");
        KeyMetadata current = require(KeyPurpose.ENCRYPTION);
        byte[] dek = store.unwrap(ownEntry(current, sealed.keys()));
        try {
            return Hlxs.open(dek, sealed.header(), sealed.bytesUnsafe(), aad);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private WrappedKey ownEntry(KeyMetadata current, List<WrappedKey> entries) {
        for (WrappedKey entry : entries) {
            if (entry.keyId().equals(current.keyId()) && store.opens(entry)) {
                return entry;
            }
        }
        throw new InvalidArgumentException("The data isn't sealed under key " + name + " (" + current.keyId()
                + "): it's sealed under " + entries.stream().map(WrappedKey::keyId).toList(), null, provider(), null);
    }

    @Override
    public OutputStream encryptingStream(OutputStream sink, byte[] aad) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(aad, "aad");
        require(KeyPurpose.ENCRYPTION);
        Lease lease = lease();
        try {
            return sealingStream(sink, aad, lease.dek(), List.of(lease.wrapped()), store.config().segmentSize());
        } finally {
            Arrays.fill(lease.dek(), (byte) 0);
        }
    }

    static OutputStream sealingStream(OutputStream sink, byte[] aad, byte[] dek, List<WrappedKey> entries,
                                      int segmentSize) {
        byte[] salt = Hlxs.randomSalt();
        byte[] noncePrefix = Hlxs.randomNoncePrefix();
        byte[] header = Hlxs.encode(segmentSize, salt, noncePrefix, Hlxs.commitment(dek, salt, segmentSize), entries);
        return new SealingOutputStream(sink, header, new Hlxs.Segments(dek, salt, noncePrefix, segmentSize, aad));
    }

    @Override
    public DataKey generateDataKey() {
        require(KeyPurpose.ENCRYPTION);
        byte[] dek = Hlxs.newDataKey();
        return new DataKey(dek, wrapNow(dek));
    }

    @Override
    public DataKey decryptDataKey(WrappedKey wrapped) {
        Objects.requireNonNull(wrapped, "wrapped");
        KeyMetadata current = require(KeyPurpose.ENCRYPTION);
        return new DataKey(store.unwrap(ownEntry(current, List.of(wrapped))), wrapped);
    }

    @Override
    public WrappedKey wrap(DataKey dataKey) {
        Objects.requireNonNull(dataKey, "dataKey");
        require(KeyPurpose.ENCRYPTION);
        return wrapNow(dataKey.plaintextUnsafe());
    }

    // --- rewrapping -------------------------------------------------------------------------------------------

    @Override
    public SealedData rewrap(SealedData sealed) {
        return rewrap(sealed, store);
    }

    @Override
    public SealedData rewrap(SealedData sealed, KeyStore from) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(from, "from");
        KeyMetadata current = require(KeyPurpose.ENCRYPTION);
        WrappedKey source;
        WrappedKey replacement;
        if (from == store && store.capabilities().supports(KeyCapability.SERVER_SIDE_REWRAP)) {
            // The data key never leaves the key service; a forged entry still fails the commitment when opened.
            source = store.entryInLocation(sealed.keys());
            replacement = store.call("rewrap", nativeName, () -> store.driver().rewrapKey(source, nativeName));
            store.stats().wrapped();
        } else {
            Opened opened = open(sealed, from);
            source = opened.entry();
            try {
                replacement = wrapNow(opened.dek());
            } finally {
                Arrays.fill(opened.dek(), (byte) 0);
            }
        }
        List<WrappedKey> entries = new ArrayList<>();
        for (WrappedKey entry : sealed.keys()) {
            if (entry.equals(source)) {
                entries.add(replacement);
            } else if (!entry.keyId().equals(current.keyId())) {
                entries.add(entry);
            }
        }
        return sealed.withKeys(entries);
    }

    @Override
    public SealedData addTo(SealedData sealed, KeyStore from) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(from, "from");
        KeyMetadata current = require(KeyPurpose.ENCRYPTION);
        Opened opened = open(sealed, from);
        WrappedKey added;
        try {
            added = wrapNow(opened.dek());
        } finally {
            Arrays.fill(opened.dek(), (byte) 0);
        }
        List<WrappedKey> entries = new ArrayList<>();
        for (WrappedKey entry : sealed.keys()) {
            if (!entry.keyId().equals(current.keyId())) {
                entries.add(entry);
            }
        }
        entries.add(added);
        return sealed.withKeys(entries);
    }

    private record Opened(WrappedKey entry, byte[] dek) {
    }

    /** The data key of {@code sealed}, through the first entry {@code from} can open, checked against the commitment. */
    private static Opened open(SealedData sealed, KeyStore from) {
        if (from instanceof DefaultKeyStore portable) {
            WrappedKey entry = portable.entryInLocation(sealed.keys());
            byte[] dek = portable.unwrap(entry);
            return committed(sealed, entry, dek);
        }
        CloudException failure = null;
        for (WrappedKey entry : sealed.keys()) {
            try (DataKey dataKey = from.decryptDataKey(entry)) {
                return committed(sealed, entry, dataKey.plaintext());
            } catch (InvalidArgumentException e) {
                failure = e; // not this store's key: try the next entry
            }
        }
        throw failure != null ? failure : new InvalidArgumentException("The sealed data has no key entries");
    }

    private static Opened committed(SealedData sealed, WrappedKey entry, byte[] dek) {
        try {
            Hlxs.checkCommitment(dek, sealed.header());
        } catch (RuntimeException e) {
            Arrays.fill(dek, (byte) 0);
            throw e;
        }
        return new Opened(entry, dek);
    }

    // --- signing and MACs -------------------------------------------------------------------------------------

    @Override
    public Signature sign(byte[] data) {
        Objects.requireNonNull(data, "data");
        KeyMetadata current = require(KeyPurpose.SIGNING);
        SigningAlgorithm algorithm = algorithm(current);
        return signDigest(current, algorithm, digest(algorithm, data));
    }

    @Override
    public Signature signDigest(byte[] digest) {
        Objects.requireNonNull(digest, "digest");
        KeyMetadata current = require(KeyPurpose.SIGNING);
        return signDigest(current, algorithm(current), digest);
    }

    private Signature signDigest(KeyMetadata current, SigningAlgorithm algorithm, byte[] digest) {
        checkDigest(algorithm, digest);
        Optional<String> version = signingVersion(current);
        byte[] signature = store.call("sign", nativeName,
                () -> store.driver().signDigest(nativeName, version, digest, algorithm));
        store.stats().signed();
        return new Signature(signature, algorithm, version);
    }

    private void checkDigest(SigningAlgorithm algorithm, byte[] digest) {
        if (digest.length != algorithm.digestLength()) {
            throw new InvalidArgumentException(algorithm + " signs a " + algorithm.digestLength() + "-byte "
                    + algorithm.digestAlgorithm() + " digest, not " + digest.length + " bytes", null, provider(), null);
        }
    }

    static byte[] digest(SigningAlgorithm algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm.digestAlgorithm()).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm.digestAlgorithm() + " is unavailable", e);
        }
    }

    @Override
    public boolean verify(byte[] data, Signature signature) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(signature, "signature");
        KeyMetadata current = require(KeyPurpose.SIGNING);
        if (!current.signingAlgorithms().contains(signature.algorithm())) {
            return false;
        }
        PublicKey key;
        try {
            key = store.publicKey(current.keyId(), nativeName, signature.keyVersion().or(current::currentVersion));
        } catch (NotFoundException e) {
            return false; // a version this key never had
        }
        return signature.algorithm().verify(key, data, signature.bytesUnsafe());
    }

    @Override
    public byte[] mac(byte[] data) {
        Objects.requireNonNull(data, "data");
        requireMac();
        byte[] mac = store.call("mac", nativeName, () -> store.driver().generateMac(nativeName, data));
        store.stats().maced();
        return mac;
    }

    @Override
    public boolean verifyMac(byte[] data, byte[] mac) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(mac, "mac");
        requireMac();
        boolean valid = store.call("verifyMac", nativeName, () -> store.driver().verifyMac(nativeName, data, mac));
        store.stats().maced();
        return valid;
    }

    private void requireMac() {
        store.capabilities().require(KeyCapability.MAC, provider());
        require(KeyPurpose.MAC);
    }

    // --- public keys ------------------------------------------------------------------------------------------

    @Override
    public PublicKey publicKey(String version) {
        Objects.requireNonNull(version, "version");
        KeyMetadata current = require(KeyPurpose.SIGNING);
        return store.publicKey(current.keyId(), nativeName, version.isEmpty() ? Optional.empty() : Optional.of(version));
    }

    @Override
    public PublicKey currentPublicKey() {
        KeyMetadata current = require(KeyPurpose.SIGNING);
        return store.publicKey(current.keyId(), nativeName, signingVersion(current));
    }

    @Override
    public Map<String, PublicKey> publicKeys() {
        KeyMetadata current = require(KeyPurpose.SIGNING);
        if (current.versions().isEmpty()) {
            return Map.of("", currentPublicKey());
        }
        Map<String, PublicKey> all = new LinkedHashMap<>();
        for (String version : current.versions()) {
            all.put(version, store.publicKey(current.keyId(), nativeName, Optional.of(version)));
        }
        return Map.copyOf(all);
    }

    @Override
    public Capabilities capabilities() {
        KeyMetadata current = metadata();
        Set<KeyCapability> supported = EnumSet.noneOf(KeyCapability.class);
        Set<KeyPurpose> purposes = purposes(current);
        if (purposes.contains(KeyPurpose.SIGNING)) {
            supported.add(KeyCapability.SIGN);
        }
        if (purposes.contains(KeyPurpose.MAC) && store.capabilities().supports(KeyCapability.MAC)) {
            supported.add(KeyCapability.MAC);
        }
        if (store.capabilities().supports(KeyCapability.SERVER_SIDE_REWRAP)) {
            supported.add(KeyCapability.SERVER_SIDE_REWRAP);
        }
        return Capabilities.of(supported.toArray(Capability[]::new));
    }

    @Override
    public AsyncCryptoKey async() {
        return async;
    }

    @Override
    public AsyncCryptoKey async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return store.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "CryptoKey[" + provider() + ":" + name + "]";
    }

    /** The asynchronous view: metadata, then the driver's non-blocking path, with local crypto on completion. */
    private final class Async implements AsyncCryptoKey {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultCryptoKey.this.provider();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CompletableFuture<KeyMetadata> metadata() {
            return metadataAsync(blocking);
        }

        private CompletableFuture<KeyMetadata> requireAsync(KeyPurpose purpose) {
            return metadataAsync(blocking).thenApply(current -> {
                require(current, purpose);
                return current;
            });
        }

        @Override
        public CompletableFuture<SealedData> encrypt(byte[] plaintext, byte[] aad) {
            try {
                Objects.requireNonNull(plaintext, "plaintext");
                Objects.requireNonNull(aad, "aad");
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            if (store.config().dataKeyReuse().isPresent()) {
                // Reuse shares one wrapped key under a lock; the wrap is rare, so the blocking path is enough.
                return CompletableFuture.supplyAsync(() -> DefaultCryptoKey.this.encrypt(plaintext, aad), blocking);
            }
            return requireAsync(KeyPurpose.ENCRYPTION).thenCompose(current -> {
                byte[] dek = Hlxs.newDataKey();
                return wrapAsync(dek).handle((wrapped, error) -> {
                    try {
                        if (error != null) {
                            throw new CompletionException(error);
                        }
                        return seal(new Lease(dek, wrapped), plaintext, aad);
                    } finally {
                        Arrays.fill(dek, (byte) 0);
                    }
                });
            });
        }

        private CompletableFuture<WrappedKey> wrapAsync(byte[] dek) {
            return store.callAsync("wrap", nativeName,
                    () -> store.driver().wrapKeyAsync(nativeName, options.version(), dek, blocking), blocking)
                    .thenApply(wrapped -> {
                        store.stats().wrapped();
                        return wrapped;
                    });
        }

        @Override
        public CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad) {
            try {
                Objects.requireNonNull(sealed, "sealed");
                Objects.requireNonNull(aad, "aad");
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return requireAsync(KeyPurpose.ENCRYPTION)
                    .thenCompose(current -> store.unwrapAsync(ownEntry(current, sealed.keys()), blocking))
                    .thenApply(dek -> {
                        try {
                            return Hlxs.open(dek, sealed.header(), sealed.bytesUnsafe(), aad);
                        } finally {
                            Arrays.fill(dek, (byte) 0);
                        }
                    });
        }

        @Override
        public CompletableFuture<DataKey> generateDataKey() {
            return requireAsync(KeyPurpose.ENCRYPTION).thenCompose(current -> {
                byte[] dek = Hlxs.newDataKey();
                return wrapAsync(dek).whenComplete((wrapped, error) -> {
                    if (error != null) {
                        Arrays.fill(dek, (byte) 0);
                    }
                }).thenApply(wrapped -> new DataKey(dek, wrapped));
            });
        }

        @Override
        public CompletableFuture<Signature> sign(byte[] data) {
            try {
                Objects.requireNonNull(data, "data");
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return requireAsync(KeyPurpose.SIGNING).thenCompose(current -> {
                SigningAlgorithm algorithm = algorithm(current);
                return signAsync(current, algorithm, digest(algorithm, data));
            });
        }

        @Override
        public CompletableFuture<Signature> signDigest(byte[] digest) {
            try {
                Objects.requireNonNull(digest, "digest");
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return requireAsync(KeyPurpose.SIGNING).thenCompose(current -> signAsync(current, algorithm(current), digest));
        }

        private CompletableFuture<Signature> signAsync(KeyMetadata current, SigningAlgorithm algorithm, byte[] digest) {
            checkDigest(algorithm, digest);
            Optional<String> version = signingVersion(current);
            return store.callAsync("sign", nativeName,
                    () -> store.driver().signDigestAsync(nativeName, version, digest, algorithm, blocking), blocking)
                    .thenApply(bytes -> {
                        store.stats().signed();
                        return new Signature(bytes, algorithm, version);
                    });
        }

        @Override
        public CompletableFuture<Boolean> verify(byte[] data, Signature signature) {
            try {
                Objects.requireNonNull(data, "data");
                Objects.requireNonNull(signature, "signature");
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return requireAsync(KeyPurpose.SIGNING).thenCompose(current -> {
                if (!current.signingAlgorithms().contains(signature.algorithm())) {
                    return CompletableFuture.completedFuture(false);
                }
                return store.publicKeyAsync(current.keyId(), nativeName,
                                signature.keyVersion().or(current::currentVersion), blocking)
                        .thenApply(key -> signature.algorithm().verify(key, data, signature.bytesUnsafe()))
                        .exceptionally(error -> {
                            if (AsyncInvocation.unwrap(error) instanceof NotFoundException) {
                                return false;
                            }
                            throw error instanceof RuntimeException r ? r
                                    : new CompletionException(error);
                        });
            });
        }

        @Override
        public CompletableFuture<byte[]> mac(byte[] data) {
            try {
                Objects.requireNonNull(data, "data");
                store.capabilities().require(KeyCapability.MAC, provider());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return requireAsync(KeyPurpose.MAC).thenCompose(current -> store.callAsync("mac", nativeName,
                    () -> store.driver().generateMacAsync(nativeName, data, blocking), blocking))
                    .thenApply(mac -> {
                        store.stats().maced();
                        return mac;
                    });
        }

        @Override
        public CompletableFuture<Boolean> verifyMac(byte[] data, byte[] mac) {
            try {
                Objects.requireNonNull(data, "data");
                Objects.requireNonNull(mac, "mac");
                store.capabilities().require(KeyCapability.MAC, provider());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return requireAsync(KeyPurpose.MAC).thenCompose(current -> store.callAsync("verifyMac", nativeName,
                    () -> store.driver().verifyMacAsync(nativeName, data, mac, blocking), blocking))
                    .thenApply(valid -> {
                        store.stats().maced();
                        return valid;
                    });
        }

        @Override
        public CryptoKey blocking() {
            return DefaultCryptoKey.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultCryptoKey.this;
        }
    }
}
