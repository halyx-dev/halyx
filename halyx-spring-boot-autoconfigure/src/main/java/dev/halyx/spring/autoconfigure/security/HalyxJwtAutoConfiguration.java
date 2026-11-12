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
import com.nimbusds.jose.jwk.source.JWKSecurityContextJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.halyx.keys.CryptoKey;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.IgnoredSpringProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.Set;

/**
 * With Spring Security's JOSE support on the classpath and {@code halyx.security.jwt.key} set: a {@link JwtEncoder}
 * that signs with that key ({@link HalyxJwtEncoder}), a {@link JWKSource} of its public keys ({@link HalyxJwkSource}),
 * the JWK set served at {@code halyx.security.jwt.jwk-set-path} on Spring MVC or WebFlux, and, when
 * {@code halyx.security.jwt.decoder.enabled}, a decoder for the application's own tokens. Each bean backs off when
 * the application defines its own.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class, beforeName = {
        "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration"})
@ConditionalOnClass({JwtEncoder.class, JWKSource.class})
@ConditionalOnProperty(prefix = "halyx.security.jwt", name = "key")
@EnableConfigurationProperties(HalyxJwtProperties.class)
public class HalyxJwtAutoConfiguration {

    /**
     * The algorithms the decoders accept. Each JWK names its key's one algorithm and a token must match it, so this
     * set accepts nothing the key doesn't sign with; it saves reading the key's metadata when the decoder is made.
     */
    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256,
            JWSAlgorithm.ES384);

    @Bean
    @ConditionalOnMissingBean
    public HalyxJwtEncoder halyxJwtEncoder(ListableBeanFactory beans, HalyxJwtProperties properties) {
        return new HalyxJwtEncoder(key(beans, properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public HalyxJwkSource halyxJwkSource(ListableBeanFactory beans, HalyxJwtProperties properties) {
        return new HalyxJwkSource(key(beans, properties));
    }

    /** The {@code CryptoKey} bean {@code halyx.security.jwt.key} names. */
    static CryptoKey key(ListableBeanFactory beans, HalyxJwtProperties properties) {
        String name = properties.key();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("halyx.security.jwt.key must name a halyx.keys bean");
        }
        if (!beans.containsBean(name) || !beans.isTypeMatch(name, CryptoKey.class)) {
            throw new IllegalStateException("halyx.security.jwt.key is '" + name + "', but there is no CryptoKey bean of "
                    + "that name; configure it under halyx.keys. CryptoKey beans: "
                    + Arrays.toString(beans.getBeanNamesForType(CryptoKey.class)));
        }
        return beans.getBean(name, CryptoKey.class);
    }

    /** The JWK set on Spring MVC, as {@code application/json}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.function.RouterFunction")
    @ConditionalOnExpression("'${halyx.security.jwt.jwk-set-path:/.well-known/jwks.json}' != ''")
    static class ServletJwkSet {

        @Bean
        org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse>
                halyxJwkSetRouter(HalyxJwkSource jwks, HalyxJwtProperties properties) {
            return org.springframework.web.servlet.function.RouterFunctions.route()
                    .GET(properties.jwkSetPath(), request -> org.springframework.web.servlet.function.ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON).body(jwks.jwkSet().toString()))
                    .build();
        }
    }

    /** The JWK set on WebFlux, as {@code application/json}; read off the event loop, since it may call the key service. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.server.RouterFunction")
    @ConditionalOnExpression("'${halyx.security.jwt.jwk-set-path:/.well-known/jwks.json}' != ''")
    static class ReactiveJwkSet {

        @Bean
        org.springframework.web.reactive.function.server.RouterFunction<
                org.springframework.web.reactive.function.server.ServerResponse> halyxJwkSetRouter(
                HalyxJwkSource jwks, HalyxJwtProperties properties) {
            return org.springframework.web.reactive.function.server.RouterFunctions.route()
                    .GET(properties.jwkSetPath(), request -> Mono.fromCallable(() -> jwks.jwkSet().toString())
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(json -> org.springframework.web.reactive.function.server.ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON).bodyValue(json)))
                    .build();
        }
    }

    /** Verifies the application's own tokens against {@link HalyxJwkSource}: no key service call per token. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "halyx.security.jwt.decoder", name = "enabled", havingValue = "true")
    static class Decoders {

        @Bean
        @ConditionalOnMissingBean
        JwtDecoder halyxJwtDecoder(HalyxJwkSource jwks, HalyxJwtProperties properties, Environment environment) {
            warnIfResourceServerSet(environment);
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, jwks));
            processor.setJWTClaimsSetVerifier((claims, context) -> {
                // Spring Security's validators check the claims
            });
            NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
            decoder.setJwtValidator(validator(properties));
            return decoder;
        }
    }

    /** The reactive decoder, next to the blocking one, for WebFlux resource servers. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "halyx.security.jwt.decoder", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "reactor.core.publisher.Mono")
    static class ReactiveDecoders {

        @Bean
        @ConditionalOnMissingBean
        ReactiveJwtDecoder halyxReactiveJwtDecoder(HalyxJwkSource jwks, HalyxJwtProperties properties) {
            // The builder takes one algorithm; the customizer replaces its key selector with one for all four, still
            // over the keys the source hands the processor.
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withJwkSource(jwt -> Mono.fromCallable(() -> jwks.jwkSet().getKeys())
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapIterable(keys -> keys))
                    .jwtProcessorCustomizer(processor -> processor.setJWSKeySelector(
                            new JWSVerificationKeySelector<>(ALGORITHMS, new JWKSecurityContextJWKSet())))
                    .build();
            decoder.setJwtValidator(validator(properties));
            return decoder;
        }
    }

    private static org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt>
            validator(HalyxJwtProperties properties) {
        String issuer = properties.decoder().issuer();
        return issuer == null || issuer.isBlank() ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(issuer);
    }

    private static void warnIfResourceServerSet(Environment environment) {
        IgnoredSpringProperties.warnIfSet(environment, "spring.security.oauth2.resourceserver.jwt",
                "halyx.security.jwt.decoder.enabled registers the JwtDecoder, which verifies tokens signed by "
                        + "halyx.security.jwt.key only");
    }
}
