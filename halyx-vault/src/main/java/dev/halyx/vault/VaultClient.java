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
package dev.halyx.vault;

import dev.halyx.json.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Minimal Vault HTTP API client. Holds the current token, logs in again shortly before it expires,
 * and once more if Vault rejects it (for example after a token revocation).
 */
final class VaultClient implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI address;
    private final Optional<String> namespace;
    private final VaultLogin login;
    private final HttpClient http;
    private VaultLogin.Token token;

    VaultClient(URI address, Optional<String> namespace, VaultLogin login, Optional<Path> caCertificate) {
        String base = address.toString();
        this.address = URI.create(base.endsWith("/") ? base : base + "/");
        this.namespace = namespace;
        this.login = login;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        caCertificate.ifPresent(pem -> builder.sslContext(trusting(pem)));
        this.http = builder.build();
    }

    /** An SSL context trusting only the CA certificates in a PEM file. */
    private static SSLContext trusting(Path pem) {
        try (InputStream in = Files.newInputStream(pem)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("ca-" + i++, certificate);
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the Vault CA certificate " + pem, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Vault CA certificate " + pem, e);
        }
    }

    /** Calls {@code /v1/<path>} and returns the parsed JSON; 404 and empty responses return null. */
    Object call(String method, String path, Object body) {
        try {
            return send(method, path, body, currentToken());
        } catch (VaultHttpException e) {
            if (e.status() == 403 && login.renewable()) {
                synchronized (this) {
                    token = null;
                }
                return send(method, path, body, currentToken());
            }
            throw e;
        }
    }

    Object unauthenticated(String method, String path, Object body) {
        return send(method, path, body, null);
    }

    private synchronized String currentToken() {
        if (token == null || token.expiringSoon(Instant.now())) {
            try {
                token = login.login(this);
            } catch (VaultHttpException e) {
                // Vault rejects bad logins with 400 (for example an unknown role) as well as 403:
                // either way the credentials were refused.
                if (e.status() >= 400 && e.status() < 500) {
                    throw new VaultHttpException(403, "login rejected: " + e.getMessage());
                }
                throw e;
            }
        }
        return token.token();
    }

    private Object send(String method, String path, Object body, String vaultToken) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(address.resolve("v1/" + path))
                    .timeout(TIMEOUT)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (vaultToken != null) {
                request.header("X-Vault-Token", vaultToken);
            }
            namespace.ifPresent(ns -> request.header("X-Vault-Namespace", ns));
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 404) {
                return null;
            }
            if (status >= 300) {
                throw new VaultHttpException(status, new String(response.body()));
            }
            return response.body().length == 0 ? null : Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reach Vault at " + address + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Vault", e);
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
