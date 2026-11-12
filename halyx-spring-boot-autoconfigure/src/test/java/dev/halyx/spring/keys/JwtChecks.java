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
package dev.halyx.spring.keys;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import dev.halyx.spring.autoconfigure.security.HalyxJwkSource;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A JWT signed through a provider's key service, checked by Nimbus alone and by the Halyx decoder. */
final class JwtChecks {

    /** The properties that sign with {@code halyx.keys.<key>} and verify with the Halyx decoder. */
    static String[] properties(String key) {
        return new String[] {"halyx.security.jwt.key=" + key, "halyx.security.jwt.decoder.enabled=true"};
    }

    private JwtChecks() {
    }

    /** Signs a token, verifies it, and returns its {@code kid}. */
    static String signAndVerify(ApplicationContext context, String alg) throws Exception {
        Instant now = Instant.now();
        Jwt jwt = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("https://orders.example.com").subject("ana").issuedAt(now).expiresAt(now.plusSeconds(300))
                .build()));
        SignedJWT parsed = SignedJWT.parse(jwt.getTokenValue());
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo(alg);
        String kid = parsed.getHeader().getKeyID();
        JWK jwk = context.getBean(HalyxJwkSource.class).jwkSet().getKeyByKeyId(kid);
        assertThat(jwk).as("the JWK set lists " + kid).isNotNull();
        JWSVerifier verifier = jwk instanceof RSAKey rsa ? new RSASSAVerifier(rsa) : new ECDSAVerifier((ECKey) jwk);
        assertThat(parsed.verify(verifier)).as("Nimbus alone verifies it").isTrue();
        assertThat(context.getBean(JwtDecoder.class).decode(jwt.getTokenValue()).getSubject()).isEqualTo("ana");
        return kid;
    }
}
