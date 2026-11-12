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
package dev.halyx.spring.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.security.HalyxJwkSource;
import dev.halyx.spring.autoconfigure.security.HalyxJwtAutoConfiguration;
import dev.halyx.spring.autoconfigure.security.HalyxJwtEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JWTs signed by a halyx.keys key: the encoder, the JWK set and its endpoints, and the decoders, on Boot 3.5 and 4. */
class HalyxJwtTest {

    private static final String[] PROPERTIES = {
            "halyx.providers.local.type=memory",
            "halyx.key-stores.kms.location=tests",
            "halyx.keys.tokens.store=kms"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxJwtAutoConfiguration.class))
            .withPropertyValues(PROPERTIES)
            .withBean(BeanPostProcessor.class, HalyxJwtTest::memoryKeys);

    /** Creates a signing key per algorithm in each key store as it is made. */
    private static BeanPostProcessor memoryKeys() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KeyStore store) {
                    InMemoryKeys keys = store.unwrap(InMemoryKeys.class);
                    keys.create("rsa", KeyPurpose.SIGNING, SigningAlgorithm.RSA_PKCS1_SHA256);
                    keys.create("pss", KeyPurpose.SIGNING, SigningAlgorithm.RSA_PSS_SHA256);
                    keys.create("p256", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
                    keys.create("p384", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P384_SHA384);
                    keys.create("data", KeyPurpose.ENCRYPTION);
                }
                return bean;
            }
        };
    }

    private static JwtClaimsSet claims() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return JwtClaimsSet.builder()
                .issuer("https://orders.example.com")
                .subject("ana")
                .audience(List.of("orders-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("scope", "orders:read")
                .claim("tenant", Map.of("id", "acme", "tier", 2))
                .build();
    }

    @ParameterizedTest
    @CsvSource({"rsa, rsa-pkcs1-sha256, RS256", "pss, rsa-pss-sha256, PS256", "p256, ecdsa-p256-sha256, ES256",
            "p384, ecdsa-p384-sha384, ES384"})
    void tokensAreSignedByTheKeyAndVerifyWithItsJwkSet(String keyName, String signingAlgorithm, String alg) {
        runner.withPropertyValues("halyx.keys.tokens.name=" + keyName,
                "halyx.keys.tokens.signing-algorithm=" + signingAlgorithm, "halyx.security.jwt.key=tokens",
                "halyx.security.jwt.decoder.enabled=true",
                "halyx.security.jwt.decoder.issuer=https://orders.example.com").run(context -> {
            assertThat(context).hasNotFailed();
            JwtEncoder encoder = context.getBean(JwtEncoder.class);
            KeyStore kms = context.getBean("kms", KeyStore.class);
            assertThat(kms.statistics().metadataReads()).as("no key service call at startup").isZero();

            long signs = kms.statistics().signs();
            JwtClaimsSet claims = claims();
            Jwt jwt = encoder.encode(JwtEncoderParameters.from(claims));
            assertThat(kms.statistics().signs() - signs).isEqualTo(1);

            SignedJWT parsed = SignedJWT.parse(jwt.getTokenValue());
            assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo(alg);
            assertThat(parsed.getHeader().getType().getType()).isEqualTo("JWT");
            String kid = parsed.getHeader().getKeyID();
            assertThat(jwt.getHeaders()).containsEntry("kid", kid).containsEntry("alg", alg);
            JWKSet jwks = context.getBean(HalyxJwkSource.class).jwkSet();
            JWK jwk = jwks.getKeyByKeyId(kid);
            assertThat(jwk).as("the JWK set lists the signing version").isNotNull();
            assertThat(jwk.getAlgorithm().getName()).isEqualTo(alg);
            assertThat(jwk.isPrivate()).isFalse();
            JWSVerifier verifier = jwk instanceof RSAKey rsa ? new RSASSAVerifier(rsa) : new ECDSAVerifier((ECKey) jwk);
            assertThat(parsed.verify(verifier)).as("Nimbus alone verifies it").isTrue();
            assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(claims.getExpiresAt());
            assertThat(parsed.getJWTClaimsSet().getAudience()).containsExactly("orders-api");

            long publicKeyReads = kms.statistics().publicKeyReads();
            Jwt decoded = context.getBean(JwtDecoder.class).decode(jwt.getTokenValue());
            assertThat(decoded.getSubject()).isEqualTo("ana");
            assertThat(decoded.getClaimAsMap("tenant")).containsEntry("id", "acme");
            assertThat(context.getBean(ReactiveJwtDecoder.class).decode(jwt.getTokenValue()).block().getSubject())
                    .isEqualTo("ana");
            context.getBean(JwtDecoder.class).decode(jwt.getTokenValue());
            assertThat(kms.statistics().publicKeyReads()).as("public keys are cached")
                    .isLessThanOrEqualTo(publicKeyReads + 1);
            assertThat(kms.statistics().signs() - signs).as("verifying makes no signing call").isEqualTo(1);
        });
    }

    @Test
    void theDecoderRefusesOtherIssuersExpiredTokensAndChangedTokens() {
        runner.withPropertyValues("halyx.keys.tokens.name=p256", "halyx.security.jwt.key=tokens",
                "halyx.security.jwt.decoder.enabled=true",
                "halyx.security.jwt.decoder.issuer=https://orders.example.com").run(context -> {
            JwtEncoder encoder = context.getBean(JwtEncoder.class);
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            Instant past = Instant.now().minusSeconds(3600);

            String otherIssuer = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.from(claims())
                    .issuer("https://elsewhere.example.com").build())).getTokenValue();
            String expired = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.from(claims())
                    .issuedAt(past.minusSeconds(60)).expiresAt(past).build())).getTokenValue();
            String token = encoder.encode(JwtEncoderParameters.from(claims())).getTokenValue();
            String[] parts = token.split("\\.");
            String other = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.from(claims()).subject("bea").build()))
                    .getTokenValue();
            String changed = parts[0] + "." + other.split("\\.")[1] + "." + parts[2];

            assertThatThrownBy(() -> decoder.decode(otherIssuer)).isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decoder.decode(changed)).isInstanceOf(BadJwtException.class)
                    .hasMessageContaining("Invalid signature");
        });
    }

    @Test
    void headersFromTheParametersAreKeptButTheAlgorithmMustBeTheKeys() {
        runner.withPropertyValues("halyx.keys.tokens.name=p256", "halyx.security.jwt.key=tokens").run(context -> {
            JwtEncoder encoder = context.getBean(JwtEncoder.class);

            Jwt jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.ES256)
                    .type("at+jwt").keyId("ignored").header("x-tenant", "acme").build(), claims()));

            assertThat(jwt.getHeaders()).containsEntry("typ", "at+jwt").containsEntry("x-tenant", "acme")
                    .doesNotContainEntry("kid", "ignored");
            assertThatThrownBy(() -> encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims())))
                    .isInstanceOf(JwtEncodingException.class).hasMessageContaining("signs with ES256, not RS256");
        });
    }

    @Test
    void aKeyThatCantSignFailsOnFirstUse() {
        runner.withPropertyValues("halyx.keys.tokens.name=data", "halyx.security.jwt.key=tokens").run(context -> {
            assertThat(context).as("no key service call at startup").hasNotFailed();
            assertThatThrownBy(() -> context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(claims())))
                    .isInstanceOf(JwtEncodingException.class).hasMessageContaining("data");
        });
    }

    @Test
    void nothingIsRegisteredWithoutTheProperty() {
        runner.withPropertyValues("halyx.keys.tokens.name=p256").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(JwtEncoder.class).doesNotHaveBean(HalyxJwkSource.class)
                .doesNotHaveBean(JwtDecoder.class));
    }

    @Test
    void aMissingKeyBeanFailsAtStartup() {
        runner.withPropertyValues("halyx.keys.tokens.name=p256", "halyx.security.jwt.key=signing")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("halyx.security.jwt.key is 'signing'").hasMessageContaining("[tokens]"));
    }

    @Test
    void theDecoderIsOptInAndBacksOffForTheApplicationsOwn() {
        runner.withPropertyValues("halyx.keys.tokens.name=p256", "halyx.security.jwt.key=tokens")
                .run(context -> assertThat(context).hasSingleBean(HalyxJwtEncoder.class)
                        .doesNotHaveBean(JwtDecoder.class).doesNotHaveBean(ReactiveJwtDecoder.class));
        JwtDecoder own = token -> {
            throw new UnsupportedOperationException();
        };
        runner.withPropertyValues("halyx.keys.tokens.name=p256", "halyx.security.jwt.key=tokens",
                        "halyx.security.jwt.decoder.enabled=true")
                .withBean(JwtDecoder.class, () -> own)
                .run(context -> assertThat(context.getBean(JwtDecoder.class)).isSameAs(own));
    }

    @Test
    void theJwkSetIsServedOnSpringMvc() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxJwtAutoConfiguration.class))
                .withPropertyValues(PROPERTIES)
                .withPropertyValues("halyx.keys.tokens.name=rsa", "halyx.keys.tokens.signing-algorithm=rsa-pkcs1-sha256",
                        "halyx.security.jwt.key=tokens")
                .withBean(BeanPostProcessor.class, HalyxJwtTest::memoryKeys)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    var router = (org.springframework.web.servlet.function.RouterFunction<
                            org.springframework.web.servlet.function.ServerResponse>) context.getBean("halyxJwkSetRouter");
                    MockMvc mvc = MockMvcBuilders.routerFunctions(router).build();
                    String json = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                            .andExpect(content().contentTypeCompatibleWith("application/json"))
                            .andReturn().getResponse().getContentAsString();
                    JWKSet served = JWKSet.parse(json);
                    assertThat(served.getKeys()).hasSize(1);
                    assertThat(served.getKeys().getFirst().isPrivate()).isFalse();
                    assertThat(served.getKeys().getFirst().getAlgorithm().getName()).isEqualTo("RS256");
                });
    }

    @Test
    void theJwkSetIsServedOnWebFluxAtTheConfiguredPath() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxJwtAutoConfiguration.class))
                .withPropertyValues(PROPERTIES)
                .withPropertyValues("halyx.keys.tokens.name=p384", "halyx.security.jwt.key=tokens",
                        "halyx.security.jwt.jwk-set-path=/oauth2/jwks")
                .withBean(BeanPostProcessor.class, HalyxJwtTest::memoryKeys)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    var router = (org.springframework.web.reactive.function.server.RouterFunction<
                            org.springframework.web.reactive.function.server.ServerResponse>)
                            context.getBean("halyxJwkSetRouter");
                    String json = WebTestClient.bindToRouterFunction(router).build().get().uri("/oauth2/jwks")
                            .exchange().expectStatus().isOk().expectBody(String.class).returnResult()
                            .getResponseBody();
                    assertThat(JWKSet.parse(json).getKeys().getFirst().getAlgorithm().getName()).isEqualTo("ES384");
                });
    }

    @Test
    void aBlankPathServesNoJwkSet() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxJwtAutoConfiguration.class))
                .withPropertyValues(PROPERTIES)
                .withPropertyValues("halyx.keys.tokens.name=rsa", "halyx.security.jwt.key=tokens",
                        "halyx.security.jwt.jwk-set-path=")
                .withBean(BeanPostProcessor.class, HalyxJwtTest::memoryKeys)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("halyxJwkSetRouter")
                        .hasSingleBean(HalyxJwkSource.class));
    }

    @Test
    void newVersionsAreListedAndOldTokensStillVerify() {
        runner.withPropertyValues("halyx.keys.tokens.name=p256", "halyx.security.jwt.key=tokens",
                "halyx.security.jwt.decoder.enabled=true").run(context -> {
            JwtEncoder encoder = context.getBean(JwtEncoder.class);
            String before = encoder.encode(JwtEncoderParameters.from(claims())).getTokenValue();
            KeyStore kms = context.getBean("kms", KeyStore.class);

            String version = kms.unwrap(InMemoryKeys.class).rotate("p256");
            // A new handle reads the key's metadata now; the bean's handle sees the version within five minutes.
            HalyxJwkSource fresh = new HalyxJwkSource(kms.key("p256", KeyOptions.builder()
                    .signingAlgorithm(SigningAlgorithm.ECDSA_P256_SHA256).build()));

            assertThat(fresh.jwkSet().getKeys()).extracting(JWK::getKeyID).contains(version)
                    .contains(SignedJWT.parse(before).getHeader().getKeyID());
        });
    }
}
