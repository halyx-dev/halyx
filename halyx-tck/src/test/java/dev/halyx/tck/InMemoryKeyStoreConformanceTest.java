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
package dev.halyx.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.memory.InMemoryKeys;

import java.util.Optional;

class InMemoryKeyStoreConformanceTest extends KeyStoreConformanceTest {

    private static Halyx halyx;
    private static InMemoryKeys keys;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        keys = halyx.provider("test").keyStore().unwrap(InMemoryKeys.class);
        keys.create("tck-data", KeyPurpose.ENCRYPTION);
        keys.create("tck-other", KeyPurpose.ENCRYPTION);
        keys.create("tck-disposable", KeyPurpose.ENCRYPTION);
        keys.create("tck-mac", KeyPurpose.MAC);
        keys.create("tck-rsa", KeyPurpose.SIGNING, SigningAlgorithm.RSA_PSS_SHA256);
        keys.create("tck-p256", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
        keys.create("tck-p384", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P384_SHA384);
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Override
    protected CloudContext context() {
        return halyx.provider("test");
    }

    @Override
    protected KeyStoreConfig.Builder config() {
        return KeyStoreConfig.builder();
    }

    @Override
    protected String encryptionKey() {
        return "tck-data";
    }

    @Override
    protected String otherEncryptionKey() {
        return "tck-other";
    }

    @Override
    protected String disposableKey() {
        return "tck-disposable";
    }

    @Override
    protected String signingKey(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> "tck-rsa";
            case ECDSA_P256_SHA256 -> "tck-p256";
            case ECDSA_P384_SHA384 -> "tck-p384";
        };
    }

    @Override
    protected Optional<String> macKey() {
        return Optional.of("tck-mac");
    }

    @Override
    protected void rotate(String name) {
        keys.rotate(name);
    }

    @Override
    protected void disable(String name) {
        keys.disable(name);
    }

    @Override
    protected void enable(String name) {
        keys.enable(name);
    }
}
