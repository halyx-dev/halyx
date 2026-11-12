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
package dev.halyx.nosql;

import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;

import java.util.Objects;
import java.util.function.Function;

/**
 * A document field the store encrypts on the client with a key from a key service
 * ({@link TableConfig.Builder#encryptedField}). The key is fixed, or picked per item from a key store by the item's
 * key, as for per-tenant keys. Reads need no key choice: the sealed value names the key that wrapped it, and any key
 * of the field's store opens it.
 *
 * <p>Fields compare by name and by the identity of their key, store and key-name function, as claim checks do.
 */
public final class EncryptedField {

    private final String name;
    private final CryptoKey key;
    private final KeyStore store;
    private final Function<Key, String> keyName;

    private EncryptedField(String name, CryptoKey key, KeyStore store, Function<Key, String> keyName) {
        this.name = name;
        this.key = key;
        this.store = store;
        this.keyName = keyName;
    }

    static EncryptedField of(String name, CryptoKey key) {
        return new EncryptedField(name, Objects.requireNonNull(key, "key"), key.store(), null);
    }

    static EncryptedField of(String name, KeyStore store, Function<Key, String> keyName) {
        return new EncryptedField(name, null, Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(keyName, "keyName"));
    }

    /** The field's name. */
    public String name() {
        return name;
    }

    /** The store whose keys seal and open this field. */
    public KeyStore store() {
        return store;
    }

    /** The key that seals this field on the item {@code itemKey}. */
    public CryptoKey key(Key itemKey) {
        if (key != null) {
            return key;
        }
        String chosen = keyName.apply(Objects.requireNonNull(itemKey, "itemKey"));
        if (chosen == null || chosen.isBlank()) {
            throw new IllegalArgumentException("The key-name function for field '" + name + "' gave no key name for "
                    + itemKey);
        }
        return store.key(chosen);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EncryptedField other && name.equals(other.name) && key == other.key
                && store == other.store && keyName == other.keyName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, System.identityHashCode(key), System.identityHashCode(store),
                System.identityHashCode(keyName));
    }

    @Override
    public String toString() {
        return "EncryptedField[" + name + ", " + (key != null ? "key=" + key.name() : "per item") + "]";
    }
}
