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
package dev.halyx.keys.spi;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A provider's key store: one location of named keys. Drivers only ever wrap and unwrap 32-byte data keys, sign
 * digests, and read keys' metadata and public keys; envelope encryption, hashing and signature verification happen in
 * Halyx. Names reach the driver as the provider's own (prefix applied, validated). Failures may be thrown as the
 * native SDK's exceptions; {@link #translate} maps them.
 */
public interface KeyStoreDriver extends AutoCloseable {

    String provider();

    /** The key's metadata, with {@code name} set to {@code nativeName}; {@code NotFoundException} if missing. */
    KeyMetadata metadata(String nativeName);

    /** Every key whose native name starts with {@code nativePrefix} (all for ""), following every page. */
    List<KeyMetadata> list(String nativePrefix);

    /**
     * Wraps {@code dek} with the key, at {@code version} if given, else its current version. The result carries the
     * key's provider-prefixed canonical id, the version used where the provider reports one, and the wrap algorithm.
     */
    WrappedKey wrapKey(String nativeName, Optional<String> version, byte[] dek);

    /** Unwraps a data key wrapped by a key in this store, identified by {@code wrapped.keyId()}. */
    byte[] unwrapKey(WrappedKey wrapped);

    /**
     * Rewraps {@code wrapped} with the key {@code targetNativeName}'s current version. By default it unwraps and wraps;
     * drivers declaring {@code SERVER_SIDE_REWRAP} do it without the data key leaving the key service.
     */
    default WrappedKey rewrapKey(WrappedKey wrapped, String targetNativeName) {
        byte[] dek = unwrapKey(wrapped);
        try {
            return wrapKey(targetNativeName, Optional.empty(), dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * Signs a digest of {@code algorithm}'s hash with the key, at {@code version} if given. Returns the signature as
     * the algorithm defines it: DER for ECDSA (drivers convert a provider's raw R‖S).
     */
    byte[] signDigest(String nativeName, Optional<String> version, byte[] digest, SigningAlgorithm algorithm);

    /** The public key of {@code version}, or of the current version. */
    PublicKey publicKey(String nativeName, Optional<String> version);

    /** HMAC-SHA256 of {@code data} with the key; called only when {@code MAC} is declared. */
    default byte[] generateMac(String nativeName, byte[] data) {
        throw new UnsupportedFeatureException("MACs are not supported by provider '" + provider() + "'");
    }

    /** Whether {@code mac} is the key's HMAC-SHA256 of {@code data}; false for a mismatch, never an exception. */
    default boolean verifyMac(String nativeName, byte[] data, byte[] mac) {
        throw new UnsupportedFeatureException("MACs are not supported by provider '" + provider() + "'");
    }

    /**
     * {@link #generateMac(String, byte[])}, given the key's current version as Halyx last read it, so a driver whose
     * service needs a version can skip looking it up. The hint may be stale: a driver must still work, by looking the
     * version up, when the service refuses it. By default the hint is ignored.
     */
    default byte[] generateMac(String nativeName, Optional<String> knownVersion, byte[] data) {
        return generateMac(nativeName, data);
    }

    /**
     * {@link #verifyMac(String, byte[], byte[])}, given the key's versions as Halyx last read them, oldest first, so a
     * driver that tries each version can skip listing them. The hint may be stale: before answering false, a driver
     * must look the versions up and try any it hasn't. By default the hint is ignored.
     */
    default boolean verifyMac(String nativeName, List<String> knownVersions, byte[] data, byte[] mac) {
        return verifyMac(nativeName, data, mac);
    }

    /** Whether the provider-prefixed {@code keyId} names a key in this store's location. */
    boolean inLocation(String keyId);

    /**
     * Whether this driver unwraps data keys wrapped with {@code algorithm}. HLXS readers skip entries they can't open
     * rather than fail on them, so an entry from a newer writer doesn't stop an older one using another entry. All of
     * them by default; a driver that knows several algorithms for its scheme lists only those it implements.
     */
    default boolean unwraps(WrapAlgorithm algorithm) {
        return true;
    }

    /** An authenticated read that always reaches the key service, for health checks. */
    void ping();

    /**
     * {@link #metadata} without blocking, where the SDK allows it; by default the blocking call on {@code blocking}.
     * The same holds for each async method below. Failures may be returned or thrown; the API layer translates both.
     */
    default CompletableFuture<KeyMetadata> metadataAsync(String nativeName, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> metadata(nativeName), blocking);
    }

    default CompletableFuture<WrappedKey> wrapKeyAsync(String nativeName, Optional<String> version, byte[] dek,
                                                      Executor blocking) {
        return CompletableFuture.supplyAsync(() -> wrapKey(nativeName, version, dek), blocking);
    }

    default CompletableFuture<byte[]> unwrapKeyAsync(WrappedKey wrapped, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> unwrapKey(wrapped), blocking);
    }

    default CompletableFuture<byte[]> signDigestAsync(String nativeName, Optional<String> version, byte[] digest,
                                                     SigningAlgorithm algorithm, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> signDigest(nativeName, version, digest, algorithm), blocking);
    }

    default CompletableFuture<PublicKey> publicKeyAsync(String nativeName, Optional<String> version,
                                                       Executor blocking) {
        return CompletableFuture.supplyAsync(() -> publicKey(nativeName, version), blocking);
    }

    default CompletableFuture<byte[]> generateMacAsync(String nativeName, byte[] data, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> generateMac(nativeName, data), blocking);
    }

    default CompletableFuture<Boolean> verifyMacAsync(String nativeName, byte[] data, byte[] mac, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> verifyMac(nativeName, data, mac), blocking);
    }

    /** {@link #generateMac(String, Optional, byte[])} without blocking; by default the unhinted async call. */
    default CompletableFuture<byte[]> generateMacAsync(String nativeName, Optional<String> knownVersion, byte[] data,
                                                       Executor blocking) {
        return generateMacAsync(nativeName, data, blocking);
    }

    /** {@link #verifyMac(String, List, byte[], byte[])} without blocking; by default the unhinted async call. */
    default CompletableFuture<Boolean> verifyMacAsync(String nativeName, List<String> knownVersions, byte[] data,
                                                      byte[] mac, Executor blocking) {
        return verifyMacAsync(nativeName, data, mac, blocking);
    }

    /** Store-level features: {@code MAC}, {@code SERVER_SIDE_REWRAP}. */
    Capabilities capabilities();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
