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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the key service reports about a key. Reading it makes no cryptographic call.
 *
 * @param name              the key's name in its store: portable and without the store's prefix, or the provider's
 *                          own name with native names
 * @param keyId             the provider-prefixed canonical id written into sealed data, such as
 *                          {@code azure-kv:https://acme.vault.azure.net/keys/orders}
 * @param nativeId          the provider's own id, such as the key ARN, for server-side encryption settings
 * @param purposes          what the key can be used for
 * @param keyType           the key's type, such as {@code AES_256}, {@code RSA_2048}, {@code EC_P256} or
 *                          {@code HMAC_256}
 * @param signingAlgorithms the algorithms the key can sign with; empty unless it can sign
 * @param enabled           whether the key, or its current version, can be used
 * @param currentVersion    the version used for new wraps and signatures, where the provider has versions
 * @param versions          the enabled versions, oldest first; empty where the provider has no visible versions
 */
public record KeyMetadata(String name, String keyId, String nativeId, Set<KeyPurpose> purposes, String keyType,
                          Set<SigningAlgorithm> signingAlgorithms, boolean enabled, Optional<String> currentVersion,
                          List<String> versions) {

    public KeyMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(nativeId, "nativeId");
        purposes = Set.copyOf(purposes);
        Objects.requireNonNull(keyType, "keyType");
        signingAlgorithms = Set.copyOf(signingAlgorithms);
        Objects.requireNonNull(currentVersion, "currentVersion");
        versions = List.copyOf(versions);
    }

    KeyMetadata withName(String portableName) {
        return new KeyMetadata(portableName, keyId, nativeId, purposes, keyType, signingAlgorithms, enabled,
                currentVersion, versions);
    }
}
