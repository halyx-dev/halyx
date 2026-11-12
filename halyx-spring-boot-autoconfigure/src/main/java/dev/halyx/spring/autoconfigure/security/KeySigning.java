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
package dev.halyx.spring.autoconfigure.security;

import com.nimbusds.jose.JWSAlgorithm;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.SigningAlgorithm;

import java.util.Optional;
import java.util.Set;

/** How a signing key appears in JOSE: its {@code alg}, and the {@code kid} of each version. */
final class KeySigning {

    private KeySigning() {
    }

    /** The algorithm the key signs with, as {@code CryptoKey.sign} chooses it. */
    static SigningAlgorithm algorithm(CryptoKey key) {
        if (key.options().signingAlgorithm().isPresent()) {
            return key.options().signingAlgorithm().get();
        }
        Set<SigningAlgorithm> algorithms = key.metadata().signingAlgorithms();
        if (algorithms.size() != 1) {
            throw new IllegalStateException("Key " + key.name() + " signs with " + algorithms
                    + ": set its signing-algorithm under halyx.keys");
        }
        return algorithms.iterator().next();
    }

    /** The JWS {@code alg} of {@code algorithm}. */
    static JWSAlgorithm jws(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PKCS1_SHA256 -> JWSAlgorithm.RS256;
            case RSA_PSS_SHA256 -> JWSAlgorithm.PS256;
            case ECDSA_P256_SHA256 -> JWSAlgorithm.ES256;
            case ECDSA_P384_SHA384 -> JWSAlgorithm.ES384;
        };
    }

    /** The version the next signature will come from, as {@code CryptoKey.sign} chooses it. */
    static Optional<String> signingVersion(CryptoKey key) {
        KeyMetadata metadata = key.metadata();
        return key.options().version().or(metadata::currentVersion);
    }

    /** The {@code kid} of a version: the version, or the key's name where the provider has none (AWS). */
    static String kid(CryptoKey key, Optional<String> version) {
        return version.filter(v -> !v.isEmpty()).orElse(key.name());
    }
}
