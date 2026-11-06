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
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.keys.spi.KeyStoreDriver;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The portable key store: name checks and prefixes, the data key and public key caches, statistics, and every driver
 * call through the interceptors with its errors translated. Its {@link CryptoKey}s do the envelope encryption, hashing
 * and local verification.
 */
public final class DefaultKeyStore implements KeyStore, AutoCloseable {

    static final String SERVICE = "keys";
    /** Characters every provider accepts in a key name: Azure's and Google Cloud's rules overlap here. */
    private static final Pattern PORTABLE_NAME = Pattern.compile("[A-Za-z0-9-]{1,63}");

    private final KeyStoreConfig config;
    private final KeyStoreDriver driver;
    private final InterceptorChain interceptors;
    private final KeyStoreStatistics statistics = new KeyStoreStatistics();
    private final DataKeyCacheMap dataKeyCache;
    private final Map<String, PublicKey> publicKeys = new ConcurrentHashMap<>();
    private final Map<KeyHandle, DefaultCryptoKey> keys = new ConcurrentHashMap<>();
    private final AsyncKeyStore async = new Async(InterceptorChain.blocking());

    private record KeyHandle(String name, KeyOptions options) {
    }

    public DefaultKeyStore(KeyStoreConfig config, KeyStoreDriver driver, InterceptorChain interceptors) {
        this.config = Objects.requireNonNull(config, "config");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        this.dataKeyCache = config.dataKeyCache().map(DataKeyCacheMap::new).orElse(null);
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public Optional<String> location() {
        return config.location();
    }

    @Override
    public CryptoKey key(String name) {
        return key(name, KeyOptions.defaults());
    }

    @Override
    public CryptoKey key(String name, KeyOptions options) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(options, "options");
        String nativeName = nativeName(name);
        return keys.computeIfAbsent(new KeyHandle(name, options),
                handle -> new DefaultCryptoKey(this, name, nativeName, options));
    }

    @Override
    public List<KeyMetadata> keys(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String nativePrefix = config.prefix() + prefix;
        return call("list", nativePrefix, () -> driver.list(nativePrefix)).stream()
                .map(metadata -> metadata.withName(portableName(metadata.name())))
                .toList();
    }

    @Override
    public byte[] decrypt(SealedData sealed, byte[] aad) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(aad, "aad");
        WrappedKey entry = entryInLocation(sealed.keys());
        byte[] dek = unwrap(entry);
        try {
            return Hlxs.open(dek, sealed.header(), sealed.bytesUnsafe(), aad);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public DataKey decryptDataKey(WrappedKey wrapped) {
        Objects.requireNonNull(wrapped, "wrapped");
        if (!opens(wrapped)) {
            throw notInLocation(List.of(wrapped));
        }
        return new DataKey(unwrap(wrapped), wrapped);
    }

    @Override
    public InputStream decryptingStream(InputStream sealed, byte[] aad) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(aad, "aad");
        DataInputStream in = new DataInputStream(sealed);
        Hlxs.Header header;
        try {
            header = Hlxs.parseHeader(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the sealed data's header", e);
        }
        byte[] dek = unwrap(entryInLocation(header.keys()));
        try {
            Hlxs.checkCommitment(dek, header);
            return new OpeningInputStream(in, new Hlxs.Segments(dek, header.salt(), header.noncePrefix(),
                    header.segmentSize(), aad));
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public InputStream decryptingStream(SealedHeader header, InputStream segments, long sealedLength,
                                        long plaintextOffset, long plaintextLength, byte[] aad) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(aad, "aad");
        long total = header.plaintextLength(sealedLength);
        if (plaintextOffset < 0 || plaintextLength < 0 || plaintextOffset > total
                || plaintextLength > total - plaintextOffset) {
            throw new InvalidArgumentException("Plaintext range " + plaintextOffset + "+" + plaintextLength
                    + " is outside the sealed data's " + total + " bytes");
        }
        Hlxs.Header parsed = header.header();
        byte[] dek = unwrap(entryInLocation(parsed.keys()));
        Hlxs.Segments opened;
        try {
            Hlxs.checkCommitment(dek, parsed);
            opened = new Hlxs.Segments(dek, parsed.salt(), parsed.noncePrefix(), parsed.segmentSize(), aad);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
        int size = parsed.segmentSize();
        long finalIndex = header.segments(sealedLength) - 1;
        if (total == 0) {
            // The only segment is empty: open it anyway, so an empty blob is still authenticated.
            RangeOpeningInputStream empty = new RangeOpeningInputStream(segments, opened, 0, 0, 0, 0, 0);
            try {
                empty.nextSegment();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the sealed data", e);
            }
            return empty;
        }
        if (plaintextLength == 0) {
            return new RangeOpeningInputStream(segments, opened, 0, -1, finalIndex, 0, 0);
        }
        long first = plaintextOffset / size;
        long lastNeeded = (plaintextOffset + plaintextLength - 1) / size;
        return new RangeOpeningInputStream(segments, opened, first, lastNeeded, finalIndex,
                plaintextOffset - first * size, plaintextLength);
    }

    @Override
    public void ping() {
        call("ping", location().orElse(""), () -> {
            driver.ping();
            return null;
        });
    }

    @Override
    public KeyStoreStatistics statistics() {
        return statistics;
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public AsyncKeyStore async() {
        return async;
    }

    @Override
    public AsyncKeyStore async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(nativeType);
    }

    /** Zeroes the cached and reused data keys. */
    @Override
    public void close() {
        keys.values().forEach(DefaultCryptoKey::clearReused);
        if (dataKeyCache != null) {
            dataKeyCache.clear();
        }
    }

    @Override
    public String toString() {
        return "KeyStore[" + provider() + ":" + location().orElse("default") + "]";
    }

    // --- shared with DefaultCryptoKey -------------------------------------------------------------------------

    KeyStoreConfig config() {
        return config;
    }

    KeyStoreDriver driver() {
        return driver;
    }

    KeyStoreStatistics stats() {
        return statistics;
    }

    String nativeName(String name) {
        String full = config.prefix() + name;
        if (config.nativeNames()) {
            if (name.isBlank()) {
                throw new InvalidArgumentException("A key name must not be blank");
            }
            return full;
        }
        if (!PORTABLE_NAME.matcher(full).matches()) {
            throw new InvalidArgumentException("Key name '" + full + "' isn't portable: use 1 to 63 letters, digits "
                    + "and hyphens, prefix included, or configure the store with native names");
        }
        return full;
    }

    String portableName(String nativeName) {
        return nativeName.startsWith(config.prefix()) ? nativeName.substring(config.prefix().length()) : nativeName;
    }

    /**
     * Whether this store can open {@code entry}: a key in its location, wrapped with an algorithm the driver implements.
     * An algorithm this version of Halyx doesn't know is never one.
     */
    boolean opens(WrappedKey entry) {
        return driver.inLocation(entry.keyId()) && entry.algorithm().filter(driver::unwraps).isPresent();
    }

    /** The first entry this store can open. */
    WrappedKey entryInLocation(List<WrappedKey> entries) {
        for (WrappedKey entry : entries) {
            if (opens(entry)) {
                return entry;
            }
        }
        throw notInLocation(entries);
    }

    private InvalidArgumentException notInLocation(List<WrappedKey> entries) {
        return new InvalidArgumentException("The sealed data's keys " + entries.stream().map(WrappedKey::keyId).toList()
                + " aren't in " + this, null, provider(), null);
    }

    /** The data key in {@code wrapped}, from the cache if it's on, checked for length. The caller zeroes it. */
    byte[] unwrap(WrappedKey wrapped) {
        String cacheKey = dataKeyCache == null ? null : DataKeyCacheMap.key(wrapped);
        if (cacheKey != null) {
            byte[] cached = dataKeyCache.get(cacheKey);
            if (cached != null) {
                statistics.unwrapCacheHit();
                return cached;
            }
        }
        byte[] dek = call("unwrap", wrapped.keyId(), () -> driver.unwrapKey(wrapped));
        statistics.unwrapped();
        checkDataKey(dek);
        if (cacheKey != null) {
            dataKeyCache.put(cacheKey, dek);
        }
        return dek;
    }

    CompletableFuture<byte[]> unwrapAsync(WrappedKey wrapped, Executor blocking) {
        String cacheKey = dataKeyCache == null ? null : DataKeyCacheMap.key(wrapped);
        if (cacheKey != null) {
            byte[] cached = dataKeyCache.get(cacheKey);
            if (cached != null) {
                statistics.unwrapCacheHit();
                return CompletableFuture.completedFuture(cached);
            }
        }
        return callAsync("unwrap", wrapped.keyId(), () -> driver.unwrapKeyAsync(wrapped, blocking), blocking)
                .thenApply(dek -> {
                    statistics.unwrapped();
                    checkDataKey(dek);
                    if (cacheKey != null) {
                        dataKeyCache.put(cacheKey, dek);
                    }
                    return dek;
                });
    }

    private void checkDataKey(byte[] dek) {
        if (dek == null || dek.length != Hlxs.DEK_LENGTH) {
            throw new DataIntegrityException("The key service returned a data key of the wrong length", null,
                    provider(), null);
        }
    }

    /** The public key of {@code version} (empty: the provider has no versions), cached since it never changes. */
    PublicKey publicKey(String keyId, String nativeName, Optional<String> version) {
        String cacheKey = keyId + "@" + version.orElse("");
        PublicKey cached = publicKeys.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        PublicKey fetched = call("publicKey", nativeName, () -> driver.publicKey(nativeName, version));
        statistics.publicKeyRead();
        publicKeys.put(cacheKey, fetched);
        return fetched;
    }

    CompletableFuture<PublicKey> publicKeyAsync(String keyId, String nativeName, Optional<String> version,
                                                Executor blocking) {
        String cacheKey = keyId + "@" + version.orElse("");
        PublicKey cached = publicKeys.get(cacheKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return callAsync("publicKey", nativeName, () -> driver.publicKeyAsync(nativeName, version, blocking), blocking)
                .thenApply(fetched -> {
                    statistics.publicKeyRead();
                    publicKeys.put(cacheKey, fetched);
                    return fetched;
                });
    }

    /** Runs a driver call through the interceptors; every key operation can be replayed safely. */
    <T> T call(String operation, String resource, Supplier<T> invocation) {
        return interceptors.execute(context(operation, resource), () -> {
            try {
                return invocation.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    <T> CompletableFuture<T> callAsync(String operation, String resource, Supplier<CompletableFuture<T>> send,
                                       Executor blocking) {
        return interceptors.executeAsync(context(operation, resource), () -> {
            CompletableFuture<T> sent;
            try {
                sent = send.get();
            } catch (RuntimeException e) {
                return CompletableFuture.<T>failedFuture(driver.translate(e));
            }
            return sent.exceptionallyCompose(error -> {
                Throwable cause = AsyncInvocation.unwrap(error);
                return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
            });
        }, blocking);
    }

    private OperationContext context(String operation, String resource) {
        return new OperationContext(SERVICE, operation, provider(), resource, true);
    }

    /** The asynchronous view: the same checks, then the driver's non-blocking path. */
    private final class Async implements AsyncKeyStore {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultKeyStore.this.provider();
        }

        @Override
        public CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad) {
            WrappedKey entry;
            try {
                Objects.requireNonNull(sealed, "sealed");
                Objects.requireNonNull(aad, "aad");
                entry = entryInLocation(sealed.keys());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return unwrapAsync(entry, blocking).thenApply(dek -> {
                try {
                    return Hlxs.open(dek, sealed.header(), sealed.bytesUnsafe(), aad);
                } finally {
                    Arrays.fill(dek, (byte) 0);
                }
            });
        }

        @Override
        public CompletableFuture<DataKey> decryptDataKey(WrappedKey wrapped) {
            try {
                Objects.requireNonNull(wrapped, "wrapped");
                if (!opens(wrapped)) {
                    throw notInLocation(List.of(wrapped));
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return unwrapAsync(wrapped, blocking).thenApply(dek -> new DataKey(dek, wrapped));
        }

        @Override
        public CompletableFuture<List<KeyMetadata>> keys(String prefix) {
            return CompletableFuture.supplyAsync(() -> DefaultKeyStore.this.keys(prefix), blocking);
        }

        @Override
        public CompletableFuture<Void> ping() {
            return CompletableFuture.runAsync(DefaultKeyStore.this::ping, blocking);
        }

        @Override
        public KeyStore blocking() {
            return DefaultKeyStore.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultKeyStore.this;
        }
    }
}
