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

import dev.halyx.capability.Capabilities;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.CloudException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.json.Json;
import dev.halyx.keys.KeyCapability;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import dev.halyx.keys.spi.AbstractKeyStoreDriver;

import javax.net.ssl.SSLHandshakeException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keys on Vault's Transit engine, mounted at the store's location (default {@code transit}). Data keys are wrapped
 * with {@code encrypt}, and the wrapped key is Transit's {@code vault:v<N>:...} ciphertext as UTF-8, as the HLXS
 * {@code vault-transit} profile defines. Every call has a non-blocking path, on the JDK HTTP client.
 *
 * <p>Transit has no disabled state: a key is withdrawn by policy, and its metadata always reports it enabled. Transit
 * also creates a missing key on {@code encrypt} when the token may, so Halyx reads a key's metadata before using it
 * (the API layer does on first use), and the documented policy grants {@code update}, never {@code create}.
 */
final class VaultTransitDriver extends AbstractKeyStoreDriver {

    static final String SCHEME = "vault-transit";

    private static final Capabilities CAPABILITIES = Capabilities.of(KeyCapability.MAC,
            KeyCapability.SERVER_SIDE_REWRAP);
    private static final Pattern VERSIONED = Pattern.compile("vault:v(\\d+):(.+)", Pattern.DOTALL);
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final VaultClient client;
    private final String mount;
    private final String idPrefix;

    VaultTransitDriver(VaultClient client, String mount) {
        super(VaultProvider.ID);
        this.client = client;
        this.mount = mount;
        this.idPrefix = SCHEME + ":" + mount + "/";
    }

    // Metadata

    @Override
    public KeyMetadata metadata(String nativeName) {
        return metadata(nativeName, client.call("GET", mount + "/keys/" + nativeName, null));
    }

    @Override
    public CompletableFuture<KeyMetadata> metadataAsync(String nativeName, Executor blocking) {
        return client.callAsync("GET", mount + "/keys/" + nativeName, null, blocking)
                .thenApply(response -> metadata(nativeName, response));
    }

    private KeyMetadata metadata(String name, Object response) {
        Key key = Key.of(name, response);
        List<String> versions = new ArrayList<>();
        for (int v = key.minDecryption; v <= key.latest; v++) {
            versions.add(Integer.toString(v));
        }
        return new KeyMetadata(name, idPrefix + name, name, key.purposes(), key.portableType(), key.algorithms(),
                true, Optional.of(Integer.toString(key.latest)), versions);
    }

    @Override
    public List<KeyMetadata> list(String nativePrefix) {
        if (!(Json.at(client.call("GET", mount + "/keys?list=true", null), "data", "keys") instanceof List<?> names)) {
            return List.of(); // no keys yet
        }
        List<KeyMetadata> found = new ArrayList<>();
        for (Object entry : names) {
            String name = String.valueOf(entry);
            if (name.startsWith(nativePrefix)) {
                try {
                    found.add(metadata(name));
                } catch (NotFoundException e) {
                    // deleted since the listing
                }
            }
        }
        found.sort(Comparator.comparing(KeyMetadata::name));
        return found;
    }

    @Override
    public void ping() {
        client.call("GET", mount + "/keys?list=true", null); // 404 when the mount has no keys yet: still an answer
    }

    // Wrapping

    @Override
    public WrappedKey wrapKey(String nativeName, Optional<String> version, byte[] dek) {
        return wrapped(nativeName, client.call("POST", mount + "/encrypt/" + nativeName, encryptBody(version, dek)));
    }

    @Override
    public CompletableFuture<WrappedKey> wrapKeyAsync(String nativeName, Optional<String> version, byte[] dek,
                                                     Executor blocking) {
        return client.callAsync("POST", mount + "/encrypt/" + nativeName, encryptBody(version, dek), blocking)
                .thenApply(response -> wrapped(nativeName, response));
    }

    private static Map<String, Object> encryptBody(Optional<String> version, byte[] dek) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plaintext", B64.encodeToString(dek));
        version.ifPresent(v -> body.put("key_version", Integer.parseInt(v)));
        return body;
    }

    private WrappedKey wrapped(String name, Object response) {
        String ciphertext = string(response, "ciphertext", name);
        return new WrappedKey(idPrefix + name, Optional.of(versionOf(ciphertext, name)), WrapAlgorithm.VAULT_TRANSIT,
                ciphertext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] unwrapKey(WrappedKey wrapped) {
        String name = nameIn(wrapped);
        return plaintext(name, client.call("POST", mount + "/decrypt/" + name, decryptBody(wrapped, name)));
    }

    @Override
    public CompletableFuture<byte[]> unwrapKeyAsync(WrappedKey wrapped, Executor blocking) {
        String name;
        Map<String, Object> body;
        try {
            name = nameIn(wrapped);
            body = decryptBody(wrapped, name);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return client.callAsync("POST", mount + "/decrypt/" + name, body, blocking)
                .thenApply(response -> plaintext(name, response));
    }

    /** The key's name, after checking the entry is this store's and its ciphertext names the entry's version. */
    private String nameIn(WrappedKey wrapped) {
        if (!inLocation(wrapped.keyId())) {
            throw new InvalidArgumentException("Key " + wrapped.keyId() + " isn't in this store", null, provider, null);
        }
        return wrapped.keyId().substring(idPrefix.length());
    }

    private Map<String, Object> decryptBody(WrappedKey wrapped, String name) {
        String ciphertext = new String(wrapped.bytes(), StandardCharsets.UTF_8);
        String version = versionOf(ciphertext, name);
        // The entry's version is outside Transit's ciphertext: a mismatch means the entry was changed.
        if (wrapped.version().isPresent() && !wrapped.version().get().equals(version)) {
            throw new DataIntegrityException("The wrapped key's version doesn't match its ciphertext for key " + name,
                    null, provider, "InvalidCiphertext");
        }
        return Map.of("ciphertext", ciphertext);
    }

    private byte[] plaintext(String name, Object response) {
        return B64D.decode(string(response, "plaintext", name));
    }

    /** Rewraps on the server when the data key stays with the same key: it never leaves Vault. */
    @Override
    public WrappedKey rewrapKey(WrappedKey wrapped, String targetNativeName) {
        if (!wrapped.keyId().equals(idPrefix + targetNativeName)) {
            return super.rewrapKey(wrapped, targetNativeName);
        }
        Map<String, Object> body = decryptBody(wrapped, targetNativeName);
        return wrapped(targetNativeName, client.call("POST", mount + "/rewrap/" + targetNativeName, body));
    }

    // Signing

    @Override
    public byte[] signDigest(String nativeName, Optional<String> version, byte[] digest, SigningAlgorithm algorithm) {
        return signature(nativeName, client.call("POST", mount + "/sign/" + nativeName,
                signBody(version, digest, algorithm)));
    }

    @Override
    public CompletableFuture<byte[]> signDigestAsync(String nativeName, Optional<String> version, byte[] digest,
                                                    SigningAlgorithm algorithm, Executor blocking) {
        return client.callAsync("POST", mount + "/sign/" + nativeName, signBody(version, digest, algorithm), blocking)
                .thenApply(response -> signature(nativeName, response));
    }

    private static Map<String, Object> signBody(Optional<String> version, byte[] digest, SigningAlgorithm algorithm) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", B64.encodeToString(digest));
        body.put("prehashed", true);
        switch (algorithm) {
            case RSA_PSS_SHA256 -> {
                body.put("hash_algorithm", "sha2-256");
                body.put("signature_algorithm", "pss");
                // Transit's default, the largest salt, isn't the salt length PS256 verifiers expect.
                body.put("salt_length", "hash");
            }
            case RSA_PKCS1_SHA256 -> {
                body.put("hash_algorithm", "sha2-256");
                body.put("signature_algorithm", "pkcs1v15");
            }
            case ECDSA_P256_SHA256 -> {
                body.put("hash_algorithm", "sha2-256");
                body.put("marshaling_algorithm", "asn1");
            }
            case ECDSA_P384_SHA384 -> {
                body.put("hash_algorithm", "sha2-384");
                body.put("marshaling_algorithm", "asn1");
            }
        }
        version.ifPresent(v -> body.put("key_version", Integer.parseInt(v)));
        return body;
    }

    private byte[] signature(String name, Object response) {
        Matcher m = VERSIONED.matcher(string(response, "signature", name));
        if (!m.matches()) {
            throw new UnavailableException("Vault returned a malformed signature for key " + name, null, provider, null);
        }
        return B64D.decode(m.group(2));
    }

    @Override
    public PublicKey publicKey(String nativeName, Optional<String> version) {
        return publicKey(nativeName, version, client.call("GET", mount + "/keys/" + nativeName, null));
    }

    @Override
    public CompletableFuture<PublicKey> publicKeyAsync(String nativeName, Optional<String> version, Executor blocking) {
        return client.callAsync("GET", mount + "/keys/" + nativeName, null, blocking)
                .thenApply(response -> publicKey(nativeName, version, response));
    }

    private PublicKey publicKey(String name, Optional<String> version, Object response) {
        Key key = Key.of(name, response);
        if (!key.purposes().contains(KeyPurpose.SIGNING)) {
            throw new InvalidArgumentException("Key " + name + " (" + key.type + ") has no public key", null, provider,
                    "InvalidKeyUsage");
        }
        String v = version.orElse(Integer.toString(key.latest));
        if (!(Json.at(response, "data", "keys", v, "public_key") instanceof String pem)) {
            throw new NotFoundException("Key " + name + " has no version " + v, null, provider, "404");
        }
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(B64D.decode(base64));
            return KeyFactory.getInstance(key.type.startsWith("rsa") ? "RSA" : "EC").generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new UnavailableException("Vault returned an unreadable public key for key " + name, e, provider,
                    null);
        }
    }

    // MACs

    @Override
    public byte[] generateMac(String nativeName, byte[] data) {
        return mac(nativeName, client.call("POST", mount + "/hmac/" + nativeName + "/sha2-256", macBody(data)));
    }

    @Override
    public CompletableFuture<byte[]> generateMacAsync(String nativeName, byte[] data, Executor blocking) {
        return client.callAsync("POST", mount + "/hmac/" + nativeName + "/sha2-256", macBody(data), blocking)
                .thenApply(response -> mac(nativeName, response));
    }

    private static Map<String, Object> macBody(byte[] data) {
        return Map.of("input", B64.encodeToString(data));
    }

    private byte[] mac(String name, Object response) {
        Matcher m = VERSIONED.matcher(string(response, "hmac", name));
        if (!m.matches()) {
            throw new UnavailableException("Vault returned a malformed HMAC for key " + name, null, provider, null);
        }
        return B64D.decode(m.group(2));
    }

    /**
     * Checks {@code mac} against each version the key still accepts, newest first: Transit's HMAC carries its version,
     * which the raw bytes don't.
     */
    @Override
    public boolean verifyMac(String nativeName, byte[] data, byte[] mac) {
        return verifyMac(nativeName, List.of(), data, mac);
    }

    /**
     * Tries the versions Halyx knows first. Before answering false it reads the key and tries the versions it hasn't,
     * so a version made since Halyx last read the key still verifies; a mismatch costs that one read.
     */
    @Override
    public boolean verifyMac(String nativeName, List<String> knownVersions, byte[] data, byte[] mac) {
        Set<String> refused = new HashSet<>();
        if (verifyEach(nativeName, data, mac, newestFirst(knownVersions), refused, true)) {
            return true;
        }
        return verifyEach(nativeName, data, mac, newestFirst(metadata(nativeName).versions()), refused, false);
    }

    /** Whether any of {@code versions} not already in {@code refused} verifies; adds each that doesn't. */
    private boolean verifyEach(String name, byte[] data, byte[] mac, List<String> versions, Set<String> refused,
                               boolean hinted) {
        for (String version : versions) {
            if (refused.contains(version)) {
                continue;
            }
            try {
                Object response = client.call("POST", mount + "/verify/" + name + "/sha2-256",
                        verifyBody(data, mac, version));
                if (Boolean.TRUE.equals(Json.at(response, "data", "valid"))) {
                    return true;
                }
                refused.add(version);
            } catch (VaultHttpException e) {
                if (!hinted || !staleVersion(e)) {
                    throw e;
                }
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Boolean> verifyMacAsync(String nativeName, byte[] data, byte[] mac, Executor blocking) {
        return verifyMacAsync(nativeName, List.of(), data, mac, blocking);
    }

    @Override
    public CompletableFuture<Boolean> verifyMacAsync(String nativeName, List<String> knownVersions, byte[] data,
                                                     byte[] mac, Executor blocking) {
        Set<String> refused = ConcurrentHashMap.newKeySet();
        return verifyFrom(nativeName, data, mac, newestFirst(knownVersions), 0, refused, true, blocking)
                .thenCompose(valid -> valid ? CompletableFuture.completedFuture(true)
                        : metadataAsync(nativeName, blocking).thenCompose(metadata -> verifyFrom(nativeName, data, mac,
                                newestFirst(metadata.versions()), 0, refused, false, blocking)));
    }

    private CompletableFuture<Boolean> verifyFrom(String name, byte[] data, byte[] mac, List<String> versions, int i,
                                                  Set<String> refused, boolean hinted, Executor blocking) {
        if (i == versions.size()) {
            return CompletableFuture.completedFuture(false);
        }
        String version = versions.get(i);
        if (refused.contains(version)) {
            return verifyFrom(name, data, mac, versions, i + 1, refused, hinted, blocking);
        }
        return client.callAsync("POST", mount + "/verify/" + name + "/sha2-256", verifyBody(data, mac, version),
                        blocking)
                .thenApply(response -> Boolean.TRUE.equals(Json.at(response, "data", "valid")))
                .exceptionallyCompose(error -> hinted && staleVersion(error) ? CompletableFuture.completedFuture(false)
                        : CompletableFuture.failedFuture(error))
                .thenCompose(valid -> {
                    if (valid) {
                        return CompletableFuture.completedFuture(true);
                    }
                    refused.add(version);
                    return verifyFrom(name, data, mac, versions, i + 1, refused, hinted, blocking);
                });
    }

    private static List<String> newestFirst(List<String> oldestFirst) {
        List<String> versions = new ArrayList<>(oldestFirst);
        Collections.reverse(versions);
        return versions;
    }

    /**
     * Vault refuses a version below the key's minimum, or one it doesn't have, with 400: the known versions were
     * stale. The read that follows lists the versions the key accepts now, and retries the rest.
     */
    private static boolean staleVersion(Throwable error) {
        return AsyncInvocation.unwrap(error) instanceof VaultHttpException e && e.status() == 400;
    }

    private static Map<String, Object> verifyBody(byte[] data, byte[] mac, String version) {
        return Map.of("input", B64.encodeToString(data), "hmac", "vault:v" + version + ":" + B64.encodeToString(mac));
    }

    // Location, capabilities, errors

    @Override
    public boolean inLocation(String keyId) {
        return keyId.startsWith(idPrefix) && keyId.length() > idPrefix.length()
                && keyId.indexOf('/', idPrefix.length()) < 0;
    }

    @Override
    public boolean unwraps(WrapAlgorithm algorithm) {
        return algorithm == WrapAlgorithm.VAULT_TRANSIT;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    private String string(Object response, String field, String name) {
        if (response == null) {
            throw new NotFoundException("Key " + name + " not found in Transit mount " + mount, null, provider, "404");
        }
        if (!(Json.at(response, "data", field) instanceof String value)) {
            throw new UnavailableException("Vault's response for key " + name + " has no " + field, null, provider,
                    null);
        }
        return value;
    }

    private String versionOf(String ciphertext, String name) {
        Matcher m = VERSIONED.matcher(ciphertext);
        if (!m.matches()) {
            throw new DataIntegrityException("The wrapped key isn't Transit ciphertext for key " + name, null, provider,
                    "InvalidCiphertext");
        }
        return m.group(1);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        if (error instanceof VaultHttpException e) {
            // Vault's error text names the problem, never the plaintext or ciphertext it was given.
            String message = e.getMessage();
            String lower = message.toLowerCase(Locale.ROOT);
            String status = String.valueOf(e.status());
            return Optional.of(switch (e.status()) {
                case 401, 403 -> new AccessDeniedException(message, e, provider, status);
                case 404 -> new NotFoundException(message, e, provider, status);
                case 429 -> new ThrottledException(message, e, provider, status);
                case 400 -> {
                    if (lower.contains("message authentication failed") || lower.contains("invalid ciphertext")
                            || lower.contains("unable to decode") || lower.contains("invalid version")) {
                        yield new DataIntegrityException(message, e, provider, status);
                    }
                    if (lower.contains("too old") || lower.contains("disallowed by policy")) {
                        yield new AccessDeniedException(message, e, provider, status);
                    }
                    if (lower.contains("not found")) {
                        yield new NotFoundException(message, e, provider, status);
                    }
                    yield new InvalidArgumentException(message, e, provider, status);
                }
                case 405 -> new InvalidArgumentException(message, e, provider, status);
                default -> new UnavailableException(message, e, provider, status); // 5xx, sealed
            });
        }
        if (error instanceof UncheckedIOException) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof SSLHandshakeException) {
                    return Optional.of(new AccessDeniedException("TLS handshake with Vault failed (" + t.getMessage()
                            + "); if Vault uses a private CA, set '" + VaultProperties.CA_CERTIFICATE + "'", error,
                            provider, "TLS"));
                }
            }
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }

    @Override
    public String toString() {
        return "VaultTransitDriver[" + mount + "]";
    }

    /** A Transit key as {@code GET <mount>/keys/<name>} describes it. */
    private record Key(String name, String type, int latest, int minDecryption) {

        static Key of(String name, Object response) {
            if (response == null) {
                throw new NotFoundException("Key " + name + " not found", null, VaultProvider.ID, "404");
            }
            Object type = Json.at(response, "data", "type");
            Object latest = Json.at(response, "data", "latest_version");
            Object min = Json.at(response, "data", "min_decryption_version");
            if (!(type instanceof String t) || !(latest instanceof Number l)) {
                throw new UnavailableException("Vault's description of key " + name + " is incomplete", null,
                        VaultProvider.ID, null);
            }
            int minDecryption = min instanceof Number m ? Math.max(1, m.intValue()) : 1;
            return new Key(name, t, l.intValue(), minDecryption);
        }

        /**
         * RSA keys also encrypt in Transit, so the purpose follows the type the way Halyx uses keys: symmetric keys
         * wrap, asymmetric keys sign, {@code hmac} keys make MACs.
         */
        Set<KeyPurpose> purposes() {
            return switch (type) {
                case "aes128-gcm96", "aes256-gcm96", "chacha20-poly1305" -> Set.of(KeyPurpose.ENCRYPTION);
                case "hmac" -> Set.of(KeyPurpose.MAC);
                default -> type.startsWith("rsa-") || type.startsWith("ecdsa-") || type.equals("ed25519")
                        ? Set.of(KeyPurpose.SIGNING) : Set.of();
            };
        }

        Set<SigningAlgorithm> algorithms() {
            if (type.startsWith("rsa-")) {
                return Set.of(SigningAlgorithm.RSA_PSS_SHA256, SigningAlgorithm.RSA_PKCS1_SHA256);
            }
            return switch (type) {
                case "ecdsa-p256" -> Set.of(SigningAlgorithm.ECDSA_P256_SHA256);
                case "ecdsa-p384" -> Set.of(SigningAlgorithm.ECDSA_P384_SHA384);
                default -> Set.of();
            };
        }

        /** The type in Halyx's names ({@code AES_256}, {@code RSA_2048}, {@code EC_P256}), else Transit's own. */
        String portableType() {
            return switch (type) {
                case "aes128-gcm96" -> "AES_128";
                case "aes256-gcm96" -> "AES_256";
                case "ecdsa-p256" -> "EC_P256";
                case "ecdsa-p384" -> "EC_P384";
                case "hmac" -> "HMAC";
                default -> type.startsWith("rsa-") ? "RSA_" + type.substring(4)
                        : type.toUpperCase(Locale.ROOT).replace('-', '_');
            };
        }
    }
}
