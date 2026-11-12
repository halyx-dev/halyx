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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.Signature;
import dev.halyx.keys.SigningAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

import java.net.URL;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A Spring Security {@link JwtEncoder} that signs with a {@link CryptoKey}: the private key never leaves the key
 * service, which signs each token's digest. The JOSE header gets the key's {@code alg} (RS256, PS256, ES256 or ES384)
 * and a {@code kid}: the key version, or the key's name where the provider has no visible versions (AWS). Publish the
 * public keys with {@link HalyxJwkSource}, so tokens are verified with no key service call.
 *
 * <p>Each {@code encode} makes one key service call. A header from {@link JwtEncoderParameters} may set {@code typ},
 * {@code cty} and other parameters; an {@code alg} other than the key's is refused, and {@code kid} is always the
 * signing version's. Thread-safe.
 */
public final class HalyxJwtEncoder implements JwtEncoder {

    private final CryptoKey key;

    public HalyxJwtEncoder(CryptoKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The key tokens are signed with. */
    public CryptoKey key() {
        return key;
    }

    @Override
    public Jwt encode(JwtEncoderParameters parameters) throws JwtEncodingException {
        Objects.requireNonNull(parameters, "parameters");
        JwtClaimsSet claims = parameters.getClaims();
        try {
            SigningAlgorithm algorithm = KeySigning.algorithm(key);
            String kid = KeySigning.kid(key, KeySigning.signingVersion(key));
            Signed signed = sign(parameters.getJwsHeader(), claims, algorithm, kid);
            String actualKid = KeySigning.kid(key, signed.signature().keyVersion());
            if (signed.signature().algorithm() != algorithm || !actualKid.equals(kid)) {
                // The key's current version changed between reading it and signing: sign again with the header
                // naming the version that signs.
                signed = sign(parameters.getJwsHeader(), claims, signed.signature().algorithm(), actualKid);
                if (!KeySigning.kid(key, signed.signature().keyVersion()).equals(actualKid)) {
                    throw new JwtEncodingException("Key " + key.name() + " changed version while signing; try again");
                }
            }
            return new Jwt(signed.token(), claims.getIssuedAt(), claims.getExpiresAt(),
                    signed.header().toJSONObject(), claims.getClaims());
        } catch (JwtEncodingException e) {
            throw e;
        } catch (RuntimeException | JOSEException | ParseException e) {
            throw new JwtEncodingException("Couldn't sign the JWT with key " + key.name() + ": " + e.getMessage(), e);
        }
    }

    private record Signed(String token, JWSHeader header, Signature signature) {
    }

    private Signed sign(JwsHeader requested, JwtClaimsSet claims, SigningAlgorithm algorithm, String kid)
            throws JOSEException, ParseException {
        JWSAlgorithm alg = KeySigning.jws(algorithm);
        JWSHeader header = header(requested, alg, kid);
        SignedJWT jwt = new SignedJWT(header, JWTClaimsSet.parse(json(claims.getClaims())));
        KeySigner signer = new KeySigner(alg);
        jwt.sign(signer);
        return new Signed(jwt.serialize(), header, signer.signature);
    }

    private JWSHeader header(JwsHeader requested, JWSAlgorithm alg, String kid) throws ParseException {
        Map<String, Object> header = new LinkedHashMap<>();
        if (requested != null) {
            if (requested.getAlgorithm() != null && !requested.getAlgorithm().getName().equals(alg.getName())) {
                throw new JwtEncodingException("Key " + key.name() + " signs with " + alg.getName() + ", not "
                        + requested.getAlgorithm().getName());
            }
            header.putAll(json(requested.getHeaders()));
        }
        header.put("alg", alg.getName());
        header.put("kid", kid);
        header.putIfAbsent("typ", "JWT");
        return JWSHeader.parse(header);
    }

    /** Spring's header and claim values as JSON values: instants as epoch seconds, URLs and collections plainly. */
    private static Map<String, Object> json(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((name, value) -> out.put(name, json(value)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object json(Object value) {
        return switch (value) {
            case Instant instant -> instant.getEpochSecond();
            case URL url -> url.toExternalForm();
            case Map<?, ?> map -> json((Map<String, Object>) map);
            case Collection<?> collection -> new ArrayList<>(collection.stream().map(HalyxJwtEncoder::json).toList());
            case null -> null;
            default -> value;
        };
    }

    /** Has the key service sign the JWS signing input; ECDSA's DER becomes the fixed-length R||S that JWS uses. */
    private final class KeySigner implements JWSSigner {

        private final JWSAlgorithm alg;
        private final JCAContext context = new JCAContext();
        private Signature signature;

        KeySigner(JWSAlgorithm alg) {
            this.alg = alg;
        }

        @Override
        public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
            signature = key.sign(signingInput);
            byte[] bytes = signature.bytes();
            if (JWSAlgorithm.Family.EC.contains(alg)) {
                bytes = ECDSA.transcodeSignatureToConcat(bytes, ECDSA.getSignatureByteArrayLength(alg));
            }
            return Base64URL.encode(bytes);
        }

        @Override
        public Set<JWSAlgorithm> supportedJWSAlgorithms() {
            return Set.of(alg);
        }

        @Override
        public JCAContext getJCAContext() {
            return context;
        }
    }

    @Override
    public String toString() {
        return "HalyxJwtEncoder[" + key.name() + "]";
    }
}
