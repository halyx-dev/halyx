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

import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.NotFoundException;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;

import javax.crypto.KeyGenerator;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The memory provider's keys in one store location, held on the connection: real JDK keys, so sealed data and
 * signatures are real. Halyx never creates keys on a cloud, so tests create them here, reached with
 * {@code keyStore.unwrap(InMemoryKeys.class)}:
 *
 * <pre>{@code
 * InMemoryKeys keys = store.unwrap(InMemoryKeys.class);
 * keys.create("orders-data", KeyPurpose.ENCRYPTION);
 * keys.create("invoices", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
 * keys.rotate("orders-data");
 * }</pre>
 *
 * A missing key raises {@code NotFoundException} and a disabled one {@code AccessDeniedException}, as on the clouds.
 */
public final class InMemoryKeys {

    /** One key: its versions' material, oldest first. */
    static final class Key {
        final KeyPurpose purpose;
        final String type;
        final Set<SigningAlgorithm> algorithms;
        final List<Object> versions = new CopyOnWriteArrayList<>();
        volatile boolean enabled = true;

        Key(KeyPurpose purpose, String type, Set<SigningAlgorithm> algorithms) {
            this.purpose = purpose;
            this.type = type;
            this.algorithms = algorithms;
        }

        String currentVersion() {
            return Integer.toString(versions.size());
        }
    }

    private final String location;
    private final Map<String, Key> keys = new ConcurrentHashMap<>();

    InMemoryKeys(String location) {
        this.location = location;
    }

    /** Creates an encryption ({@code AES_256}) or MAC ({@code HMAC_256}) key with one version. */
    public void create(String name, KeyPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose");
        switch (purpose) {
            case ENCRYPTION -> add(name, new Key(purpose, "AES_256", Set.of()));
            case MAC -> add(name, new Key(purpose, "HMAC_256", Set.of()));
            case SIGNING -> throw new IllegalArgumentException("A signing key needs its algorithm: use "
                    + "create(name, KeyPurpose.SIGNING, algorithm)");
        }
    }

    /**
     * Creates a signing key with one version: RSA 2048, which signs with both RSA algorithms, or an EC P-256 or P-384
     * key.
     */
    public void create(String name, KeyPurpose purpose, SigningAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        if (purpose != KeyPurpose.SIGNING) {
            throw new IllegalArgumentException("Only signing keys take a signing algorithm");
        }
        add(name, switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> new Key(purpose, "RSA_2048",
                    Set.of(SigningAlgorithm.RSA_PSS_SHA256, SigningAlgorithm.RSA_PKCS1_SHA256));
            case ECDSA_P256_SHA256 -> new Key(purpose, "EC_P256", Set.of(algorithm));
            case ECDSA_P384_SHA384 -> new Key(purpose, "EC_P384", Set.of(algorithm));
        });
    }

    private void add(String name, Key key) {
        Objects.requireNonNull(name, "name");
        key.versions.add(material(key));
        if (keys.putIfAbsent(name, key) != null) {
            throw new IllegalArgumentException("Key " + name + " already exists");
        }
    }

    /** Adds a new version and makes it current; returns its version id. Older versions keep working. */
    public String rotate(String name) {
        Key key = existing(name);
        synchronized (key) {
            key.versions.add(material(key));
            return key.currentVersion();
        }
    }

    /** Disables the key: every use raises {@code AccessDeniedException} until it's enabled again. */
    public void disable(String name) {
        existing(name).enabled = false;
    }

    public void enable(String name) {
        existing(name).enabled = true;
    }

    /** Removes the key, so using it raises {@code NotFoundException}. */
    public void remove(String name) {
        Objects.requireNonNull(name, "name");
        keys.remove(name);
    }

    String location() {
        return location;
    }

    Map<String, Key> all() {
        return keys;
    }

    Key existing(String name) {
        Key key = keys.get(Objects.requireNonNull(name, "name"));
        if (key == null) {
            throw new NotFoundException("Key " + name + " does not exist", null, InMemoryProvider.ID, "NotFound");
        }
        return key;
    }

    Key usable(String name) {
        Key key = existing(name);
        if (!key.enabled) {
            throw new AccessDeniedException("Key " + name + " is disabled", null, InMemoryProvider.ID, "Disabled");
        }
        return key;
    }

    void clear() {
        keys.clear();
    }

    private static Object material(Key key) {
        try {
            return switch (key.type) {
                case "AES_256" -> {
                    KeyGenerator aes = KeyGenerator.getInstance("AES");
                    aes.init(256);
                    yield aes.generateKey();
                }
                case "HMAC_256" -> {
                    KeyGenerator hmac = KeyGenerator.getInstance("HmacSHA256");
                    hmac.init(256);
                    yield hmac.generateKey();
                }
                case "RSA_2048" -> {
                    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
                    rsa.initialize(2048);
                    yield rsa.generateKeyPair();
                }
                default -> {
                    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
                    ec.initialize(new ECGenParameterSpec(key.type.equals("EC_P256") ? "secp256r1" : "secp384r1"));
                    yield ec.generateKeyPair();
                }
            };
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate a " + key.type + " key", e);
        }
    }
}
