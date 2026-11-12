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
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.halyx.keys.CryptoKey;

import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The public keys of a signing {@link CryptoKey} as a JWK set, one JWK per enabled version, with the {@code kid} and
 * {@code alg} that {@link HalyxJwtEncoder} writes: for a JWK set endpoint, and for verifying the application's own
 * tokens. Public keys are cached by the key, and versions follow its metadata, so a new version is listed within
 * minutes of being made. Holds no private key.
 */
public final class HalyxJwkSource implements JWKSource<SecurityContext> {

    private final CryptoKey key;
    private volatile Cached cached;

    private record Cached(Map<String, PublicKey> publicKeys, JWKSet set) {
    }

    public HalyxJwkSource(CryptoKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The JWK set of the key's enabled versions; public keys only. */
    public JWKSet jwkSet() {
        Map<String, PublicKey> publicKeys = key.publicKeys();
        Cached last = cached;
        if (last != null && last.publicKeys().equals(publicKeys)) {
            return last.set();
        }
        JWSAlgorithm alg = KeySigning.jws(KeySigning.algorithm(key));
        List<JWK> jwks = new ArrayList<>();
        publicKeys.forEach((version, publicKey) -> jwks.add(jwk(publicKey, KeySigning.kid(key, Optional.of(version)),
                alg)));
        JWKSet set = new JWKSet(jwks);
        cached = new Cached(Map.copyOf(publicKeys), set);
        return set;
    }

    private JWK jwk(PublicKey publicKey, String kid, JWSAlgorithm alg) {
        return switch (publicKey) {
            case RSAPublicKey rsa -> new RSAKey.Builder(rsa).keyID(kid).algorithm(alg).keyUse(KeyUse.SIGNATURE).build();
            case ECPublicKey ec -> new ECKey.Builder(Curve.forECParameterSpec(ec.getParams()), ec).keyID(kid)
                    .algorithm(alg).keyUse(KeyUse.SIGNATURE).build();
            default -> throw new IllegalStateException("Key " + key.name() + " has a " + publicKey.getAlgorithm()
                    + " public key, which JWS can't use");
        };
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return selector.select(jwkSet());
    }

    @Override
    public String toString() {
        return "HalyxJwkSource[" + key.name() + "]";
    }
}
