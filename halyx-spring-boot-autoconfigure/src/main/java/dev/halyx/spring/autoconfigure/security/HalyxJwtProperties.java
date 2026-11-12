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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWTs signed by a key of {@code halyx.keys}:
 *
 * <pre>{@code
 * halyx:
 *   security:
 *     jwt:
 *       key: tokens                      # a halyx.keys bean: a signing key
 *       jwk-set-path: /.well-known/jwks.json
 *       decoder: { enabled: true, issuer: https://orders.example.com }
 * }</pre>
 *
 * @param key        the {@code CryptoKey} bean that signs; setting it turns the encoder and JWK set on
 * @param jwkSetPath where the JWK set is served, on Spring MVC or WebFlux; blank to serve none
 * @param decoder    a {@code JwtDecoder} for the application's own tokens
 */
@ConfigurationProperties("halyx.security.jwt")
public record HalyxJwtProperties(String key, @DefaultValue("/.well-known/jwks.json") String jwkSetPath,
                                 @DefaultValue Decoder decoder) {

    /**
     * @param enabled whether to register a {@code JwtDecoder} (and a {@code ReactiveJwtDecoder} with Reactor) that
     *                verifies tokens signed by the key, with no key service call; off by default
     * @param issuer  the {@code iss} tokens must have; not checked when unset
     */
    public record Decoder(@DefaultValue("false") boolean enabled, String issuer) {
    }
}
