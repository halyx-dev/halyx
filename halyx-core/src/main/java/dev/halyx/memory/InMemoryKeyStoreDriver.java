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
package dev.halyx.memory;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.keys.KeyCapability;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import dev.halyx.keys.spi.AbstractKeyStoreDriver;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The memory provider's key store over {@link InMemoryKeys}: AES-256-GCM key wrapping, RSA and ECDSA signatures of
 * digests, and HMAC-SHA256, all with JDK cryptography. Declares every capability.
 */
final class InMemoryKeyStoreDriver extends AbstractKeyStoreDriver {

    private static final Capabilities CAPABILITIES = Capabilities.of(KeyCapability.MAC, KeyCapability.SERVER_SIDE_REWRAP);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The DER DigestInfo prefix of a SHA-256 digest, for PKCS #1 v1.5 signatures of a digest. */
    private static final byte[] SHA256_DIGEST_INFO = {0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01,
            0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20};

    private final InMemoryKeys keys;
    private final String idPrefix;

    InMemoryKeyStoreDriver(InMemoryKeys keys) {
        super(InMemoryProvider.ID);
        this.keys = keys;
        this.idPrefix = "memory:" + keys.location() + "/";
    }

    @Override
    public KeyMetadata metadata(String nativeName) {
        return metadata(nativeName, keys.existing(nativeName));
    }

    private KeyMetadata metadata(String name, InMemoryKeys.Key key) {
        List<String> versions = IntStream.rangeClosed(1, key.versions.size()).mapToObj(Integer::toString).toList();
        return new KeyMetadata(name, idPrefix + name, name, Set.of(key.purpose), key.type, key.algorithms, key.enabled,
                Optional.of(key.currentVersion()), versions);
    }

    @Override
    public List<KeyMetadata> list(String nativePrefix) {
        List<KeyMetadata> found = new ArrayList<>();
        for (Map.Entry<String, InMemoryKeys.Key> entry : keys.all().entrySet()) {
            if (entry.getKey().startsWith(nativePrefix)) {
                found.add(metadata(entry.getKey(), entry.getValue()));
            }
        }
        found.sort((a, b) -> a.name().compareTo(b.name()));
        return found;
    }

    @Override
    public WrappedKey wrapKey(String nativeName, Optional<String> version, byte[] dek) {
        InMemoryKeys.Key key = keys.usable(nativeName);
        requirePurpose(nativeName, key, KeyPurpose.ENCRYPTION);
        String used = version.orElse(key.currentVersion());
        SecretKey material = (SecretKey) material(nativeName, key, used);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, material, new GCMParameterSpec(128, nonce));
            gcm.updateAAD((idPrefix + nativeName).getBytes(StandardCharsets.UTF_8));
            byte[] sealed = gcm.doFinal(dek);
            return new WrappedKey(idPrefix + nativeName, Optional.of(used), WrapAlgorithm.MEMORY,
                    ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    @Override
    public byte[] unwrapKey(WrappedKey wrapped) {
        if (!inLocation(wrapped.keyId())) {
            throw new InvalidArgumentException("Key " + wrapped.keyId() + " isn't in this store", null, provider, null);
        }
        String name = wrapped.keyId().substring(idPrefix.length());
        InMemoryKeys.Key key = keys.usable(name);
        requirePurpose(name, key, KeyPurpose.ENCRYPTION);
        String version = wrapped.version().orElseThrow(() -> new DataIntegrityException(
                "The wrapped key has no version", null, provider, "InvalidCiphertext"));
        SecretKey material = (SecretKey) material(name, key, version);
        byte[] bytes = wrapped.bytes();
        if (bytes.length < 12 + 16) {
            throw new DataIntegrityException("The wrapped key is truncated", null, provider, "InvalidCiphertext");
        }
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, material, new GCMParameterSpec(128, bytes, 0, 12));
            gcm.updateAAD(wrapped.keyId().getBytes(StandardCharsets.UTF_8));
            return gcm.doFinal(bytes, 12, bytes.length - 12);
        } catch (AEADBadTagException e) {
            throw new DataIntegrityException("The wrapped key is invalid for key " + name, e, provider,
                    "InvalidCiphertext");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    @Override
    public byte[] signDigest(String nativeName, Optional<String> version, byte[] digest, SigningAlgorithm algorithm) {
        InMemoryKeys.Key key = keys.usable(nativeName);
        requirePurpose(nativeName, key, KeyPurpose.SIGNING);
        if (!key.algorithms.contains(algorithm)) {
            throw new InvalidArgumentException("Key " + nativeName + " (" + key.type + ") can't sign with " + algorithm,
                    null, provider, "InvalidKeyUsage");
        }
        KeyPair pair = (KeyPair) material(nativeName, key, version.orElse(key.currentVersion()));
        try {
            return switch (algorithm) {
                case ECDSA_P256_SHA256, ECDSA_P384_SHA384 -> {
                    java.security.Signature ecdsa = java.security.Signature.getInstance("NONEwithECDSA");
                    ecdsa.initSign(pair.getPrivate());
                    ecdsa.update(digest);
                    yield ecdsa.sign();
                }
                case RSA_PKCS1_SHA256 -> {
                    java.security.Signature rsa = java.security.Signature.getInstance("NONEwithRSA");
                    rsa.initSign(pair.getPrivate());
                    rsa.update(SHA256_DIGEST_INFO);
                    rsa.update(digest);
                    yield rsa.sign();
                }
                case RSA_PSS_SHA256 -> pss((RSAPrivateKey) pair.getPrivate(), digest);
            };
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " signing failed", e);
        }
    }

    /** RSASSA-PSS (RFC 8017, 8.1.1) of a SHA-256 digest, MGF1-SHA256, 32-byte salt: the JDK only signs messages. */
    static byte[] pss(RSAPrivateKey key, byte[] digest) throws GeneralSecurityException {
        int hashLength = 32;
        int saltLength = 32;
        int modulusBits = key.getModulus().bitLength();
        int emBits = modulusBits - 1;
        int emLength = (emBits + 7) / 8;
        byte[] salt = new byte[saltLength];
        RANDOM.nextBytes(salt);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(new byte[8]);
        sha.update(digest);
        sha.update(salt);
        byte[] h = sha.digest();
        byte[] db = new byte[emLength - hashLength - 1];
        db[db.length - saltLength - 1] = 0x01;
        System.arraycopy(salt, 0, db, db.length - saltLength, saltLength);
        byte[] mask = mgf1(h, db.length);
        for (int i = 0; i < db.length; i++) {
            db[i] ^= mask[i];
        }
        db[0] &= (byte) (0xFF >>> (8 * emLength - emBits));
        byte[] em = ByteBuffer.allocate(emLength).put(db).put(h).put((byte) 0xbc).array();
        byte[] s = new BigInteger(1, em).modPow(key.getPrivateExponent(), key.getModulus()).toByteArray();
        int k = (modulusBits + 7) / 8;
        byte[] out = new byte[k];
        int copy = Math.min(s.length, k);
        System.arraycopy(s, s.length - copy, out, k - copy, copy);
        return out;
    }

    private static byte[] mgf1(byte[] seed, int length) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] out = new byte[length];
        int filled = 0;
        for (int counter = 0; filled < length; counter++) {
            sha.update(seed);
            sha.update(ByteBuffer.allocate(4).putInt(counter).array());
            byte[] block = sha.digest();
            int n = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, out, filled, n);
            filled += n;
        }
        return out;
    }

    @Override
    public PublicKey publicKey(String nativeName, Optional<String> version) {
        InMemoryKeys.Key key = keys.existing(nativeName);
        requirePurpose(nativeName, key, KeyPurpose.SIGNING);
        return ((KeyPair) material(nativeName, key, version.orElse(key.currentVersion()))).getPublic();
    }

    @Override
    public byte[] generateMac(String nativeName, byte[] data) {
        InMemoryKeys.Key key = keys.usable(nativeName);
        requirePurpose(nativeName, key, KeyPurpose.MAC);
        return hmac((SecretKey) material(nativeName, key, key.currentVersion()), data);
    }

    @Override
    public boolean verifyMac(String nativeName, byte[] data, byte[] mac) {
        InMemoryKeys.Key key = keys.usable(nativeName);
        requirePurpose(nativeName, key, KeyPurpose.MAC);
        for (Object version : key.versions) {
            if (MessageDigest.isEqual(hmac((SecretKey) version, data), mac)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] hmac(SecretKey key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    @Override
    public boolean inLocation(String keyId) {
        return keyId.startsWith(idPrefix) && keyId.length() > idPrefix.length();
    }

    @Override
    public void ping() {
        // Always answers: the keys are in this JVM.
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    private void requirePurpose(String name, InMemoryKeys.Key key, KeyPurpose purpose) {
        if (key.purpose != purpose) {
            throw new InvalidArgumentException("Key " + name + " is a " + key.purpose + " key (" + key.type
                    + "), not " + purpose, null, provider, "InvalidKeyUsage");
        }
    }

    private Object material(String name, InMemoryKeys.Key key, String version) {
        int index;
        try {
            index = Integer.parseInt(version) - 1;
        } catch (NumberFormatException e) {
            index = -1;
        }
        if (index < 0 || index >= key.versions.size()) {
            throw new NotFoundException("Key " + name + " has no version " + version, null, provider, "NotFound");
        }
        return key.versions.get(index);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(keys);
    }

    @Override
    public String toString() {
        return "InMemoryKeyStoreDriver[" + keys.location() + "]";
    }
}
