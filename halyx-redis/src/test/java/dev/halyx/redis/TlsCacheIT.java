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
package dev.halyx.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.cache.CacheConfig;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.tck.CacheConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.FileInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TLS with a private CA, as Memorystore's in-transit encryption uses: trusted through
 * {@code redis.ca-certificate}, rejected without it.
 */
@Testcontainers(disabledWithoutDocker = true)
class TlsCacheIT {

    @TempDir
    static Path dir;

    private static GenericContainer<?> valkey;
    private static Halyx halyx;
    private static Path caCertificate;

    @BeforeAll
    static void start() throws Exception {
        Path keystore = dir.resolve("server.p12");
        run(keytool(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", // not 127.0.0.1: see the verify-peer tests
                "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        caCertificate = dir.resolve("server.crt");
        run(keytool(), "-exportcert", "-rfc", "-alias", "server", "-keystore", keystore.toString(),
                "-storepass", "changeit", "-file", caCertificate.toString());
        Path privateKey = dir.resolve("server.key");
        Files.writeString(privateKey, pemPrivateKey(keystore));

        valkey = new GenericContainer<>(ValkeyCacheConformanceIT.VALKEY)
                .withExposedPorts(6379)
                .withCopyFileToContainer(MountableFile.forHostPath(caCertificate, 0644), "/tls/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(privateKey, 0644), "/tls/server.key")
                .withCommand("valkey-server", "--port", "0", "--tls-port", "6379",
                        "--tls-cert-file", "/tls/server.crt", "--tls-key-file", "/tls/server.key",
                        "--tls-ca-cert-file", "/tls/server.crt", "--tls-auth-clients", "no");
        valkey.start();
        halyx = Halyx.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
        if (valkey != null) {
            valkey.stop();
        }
    }

    private static URI endpoint() {
        return URI.create("rediss://localhost:" + valkey.getMappedPort(6379));
    }

    @Nested
    class TrustingThePrivateCa extends CacheConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint()).property(RedisCaches.CA_CERTIFICATE, caCertificate.toString());
        }
    }

    @Test
    void untrustedCertificateIsAConfigurationError() {
        var untrusted = CacheConfig.builder(endpoint()).namespace("untrusted").build();

        assertThatThrownBy(() -> halyx.provider("redis").cache(untrusted).get("k"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(RedisCaches.CA_CERTIFICATE)
                .satisfies(e -> assertThat(((AccessDeniedException) e).isRetryable()).isFalse());
    }

    /** The address the certificate doesn't name, as when connecting through a tunnel. */
    private static URI unnamedAddress() {
        return URI.create("rediss://127.0.0.1:" + valkey.getMappedPort(6379));
    }

    @Test
    void fullVerificationRejectsACertificateForAnotherName() {
        var mismatched = CacheConfig.builder(unnamedAddress())
                .property(RedisCaches.CA_CERTIFICATE, caCertificate.toString()).namespace("full").build();

        assertThatThrownBy(() -> halyx.provider("redis").cache(mismatched).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void caVerificationChecksTheChainButNotTheName() {
        var tunnelled = CacheConfig.builder(unnamedAddress())
                .property(RedisCaches.CA_CERTIFICATE, caCertificate.toString())
                .property(RedisCaches.VERIFY_PEER, "ca").namespace("ca-only").build();
        var cache = halyx.provider("redis").cache(tunnelled);
        cache.put("k", "v");

        assertThat(cache.get("k")).contains("v");
    }

    @Test
    void caVerificationStillRejectsAnUntrustedChain() {
        var untrusted = CacheConfig.builder(unnamedAddress())
                .property(RedisCaches.VERIFY_PEER, "ca").namespace("ca-untrusted").build();

        assertThatThrownBy(() -> halyx.provider("redis").cache(untrusted).get("k"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void jdkResolverConnectsWithVerifiedTls() {
        var viaJdk = CacheConfig.builder(endpoint()).property(RedisCaches.CA_CERTIFICATE, caCertificate.toString())
                .property(RedisCaches.DNS_RESOLVER, "jdk").namespace("jdk-resolver").build();
        var cache = halyx.provider("redis").cache(viaJdk);
        cache.put("k", "v");

        assertThat(cache.get("k")).contains("v");
    }

    @Test
    void unknownDnsResolverIsRejected() {
        var unknown = CacheConfig.builder(endpoint()).property(RedisCaches.DNS_RESOLVER, "bind")
                .namespace("unknown-resolver").build();

        assertThatThrownBy(() -> halyx.provider("redis").cache(unknown).get("k"))
                .hasMessageContaining(RedisCaches.DNS_RESOLVER);
    }

    private static String keytool() {
        return Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static String pemPrivateKey(Path keystore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(keystore.toFile())) {
            store.load(in, "changeit".toCharArray());
        }
        Key key = store.getKey("server", "changeit".toCharArray());
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
