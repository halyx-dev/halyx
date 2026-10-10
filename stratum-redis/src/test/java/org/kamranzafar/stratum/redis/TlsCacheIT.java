package org.kamranzafar.stratum.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.error.AccessDeniedException;
import org.kamranzafar.stratum.tck.CacheConformanceTest;
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
    private static Stratum stratum;
    private static Path caCertificate;

    @BeforeAll
    static void start() throws Exception {
        Path keystore = dir.resolve("server.p12");
        run(keytool(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
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
        stratum = Stratum.builder()
                .plugin(new RedisTestProvider(RedisCaches.iamUnsupported("redis-test")))
                .register("redis", ProviderConfig.of("redis-test"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
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
            return stratum.provider("redis");
        }

        @Override
        protected CacheConfig.Builder config() {
            return CacheConfig.builder(endpoint()).property(RedisCaches.CA_CERTIFICATE, caCertificate.toString());
        }
    }

    @Test
    void untrustedCertificateIsAConfigurationError() {
        var untrusted = CacheConfig.builder(endpoint()).namespace("untrusted").build();

        assertThatThrownBy(() -> stratum.provider("redis").cache(untrusted).get("k"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(RedisCaches.CA_CERTIFICATE)
                .satisfies(e -> assertThat(((AccessDeniedException) e).isRetryable()).isFalse());
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
