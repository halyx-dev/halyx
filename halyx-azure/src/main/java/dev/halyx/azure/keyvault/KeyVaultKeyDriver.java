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
package dev.halyx.azure.keyvault;

import com.azure.core.exception.HttpResponseException;
import com.azure.security.keyvault.keys.KeyAsyncClient;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyAsyncClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.azure.security.keyvault.keys.models.KeyType;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import dev.halyx.azure.AzureErrors;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnavailableException;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import dev.halyx.keys.spi.AbstractKeyStoreDriver;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.PublicKey;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Keys in one Azure Key Vault, the store's location. A key's name is the Key Vault key's; with native names, the
 * versionless key URL in this vault ({@code https://acme.vault.azure.net/keys/orders}) is accepted too. Data keys are
 * wrapped with {@code wrapKey} and {@code RSA-OAEP-256} on an RSA key, as the HLXS {@code azure-kv} profile defines, and
 * each entry records the key version that wrapped it.
 *
 * <p>Requests are always built from the configured vault URL and the key's name and version, never from a {@code kid}
 * the service returns: the configured vault is the source of truth, and emulators report the real Azure host there.
 * Every cryptographic operation runs on the vault ({@code disableKeyCaching}), so a disabled key or version is refused
 * there rather than used locally. Every call except {@code list} has a non-blocking path.
 */
public final class KeyVaultKeyDriver extends AbstractKeyStoreDriver {

    static final String SCHEME = "azure-kv";

    private final KeyClient keys;
    private final KeyAsyncClient asyncKeys;
    private final String vaultUrl;
    private final String origin;
    private final String path;
    private final String idPrefix;
    private final boolean nativeNames;
    private final Function<String, CryptographyClientBuilder> cryptography;
    private final Map<String, CryptographyClient> clients = new ConcurrentHashMap<>();
    private final Map<String, CryptographyAsyncClient> asyncClients = new ConcurrentHashMap<>();

    /**
     * @param vaultUrl     the vault's URL, the store's location
     * @param cryptography a builder for a key identifier ({@code <vault>/keys/<name>[/<version>]}), with the
     *                     connection's credential and HTTP client
     */
    public KeyVaultKeyDriver(KeyClient keys, KeyAsyncClient asyncKeys, String vaultUrl, boolean nativeNames,
                             Function<String, CryptographyClientBuilder> cryptography) {
        super(AzureProvider.ID);
        this.keys = keys;
        this.asyncKeys = asyncKeys;
        this.vaultUrl = normalize(vaultUrl);
        int hostEnd = this.vaultUrl.indexOf('/', this.vaultUrl.indexOf("://") + 3);
        this.origin = hostEnd < 0 ? this.vaultUrl : this.vaultUrl.substring(0, hostEnd);
        this.path = hostEnd < 0 ? "" : this.vaultUrl.substring(hostEnd);
        this.idPrefix = SCHEME + ":" + this.vaultUrl + "/keys/";
        this.nativeNames = nativeNames;
        this.cryptography = cryptography;
    }

    /** The vault URL with a lower-case scheme and host and no trailing slash, as key ids are written. */
    static String normalize(String vaultUrl) {
        String url = vaultUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        int hostEnd = url.indexOf('/', url.indexOf("://") + 3);
        return hostEnd < 0 ? url.toLowerCase(Locale.ROOT)
                : url.substring(0, hostEnd).toLowerCase(Locale.ROOT) + url.substring(hostEnd);
    }

    // Metadata

    @Override
    public KeyMetadata metadata(String nativeName) {
        String name = keyName(nativeName);
        KeyVaultKey key = keys.getKey(name);
        List<KeyProperties> versions = new ArrayList<>();
        keys.listPropertiesOfKeyVersions(name).forEach(versions::add);
        return metadata(nativeName, name, key, versions);
    }

    @Override
    public CompletableFuture<KeyMetadata> metadataAsync(String nativeName, Executor blocking) {
        String name = keyName(nativeName);
        return Mono.zip(asyncKeys.getKey(name), asyncKeys.listPropertiesOfKeyVersions(name).collectList())
                .map(both -> metadata(nativeName, name, both.getT1(), both.getT2()))
                .toFuture();
    }

    /**
     * The current version is the one {@code getKey} returns, and it decides whether the key is enabled: Key Vault
     * enables and disables versions one by one. {@code versions} lists the enabled ones, oldest first.
     */
    private KeyMetadata metadata(String nativeName, String name, KeyVaultKey key, List<KeyProperties> versions) {
        JsonWebKey jwk = key.getKey();
        String current = key.getProperties().getVersion();
        Set<KeyPurpose> purposes = purposes(jwk);
        return new KeyMetadata(nativeName, idPrefix + name, vaultUrl + "/keys/" + name, purposes, keyType(jwk),
                purposes.contains(KeyPurpose.SIGNING) ? algorithms(jwk) : Set.of(),
                Boolean.TRUE.equals(key.getProperties().isEnabled()), Optional.ofNullable(current),
                enabledOldestFirst(versions, current));
    }

    /** Versions made in the same second have equal times, so the current version is put last whatever its time. */
    static List<String> enabledOldestFirst(List<KeyProperties> versions, String current) {
        return versions.stream()
                .filter(v -> Boolean.TRUE.equals(v.isEnabled()) && v.getVersion() != null)
                .sorted(Comparator.comparing((KeyProperties v) -> v.getVersion().equals(current))
                        .thenComparing(KeyProperties::getCreatedOn, Comparator.nullsFirst(OffsetDateTime::compareTo)))
                .map(KeyProperties::getVersion)
                .toList();
    }

    @Override
    public List<KeyMetadata> list(String nativePrefix) {
        List<KeyMetadata> found = new ArrayList<>();
        for (KeyProperties properties : keys.listPropertiesOfKeys()) {
            String name = properties.getName();
            if (name == null || !name.startsWith(nativePrefix)) {
                continue;
            }
            try {
                found.add(metadata(name));
            } catch (HttpResponseException e) {
                if (e.getResponse() == null || e.getResponse().getStatusCode() != 404) {
                    throw e;
                }
                // deleted since the listing
            }
        }
        found.sort(Comparator.comparing(KeyMetadata::name));
        return found;
    }

    @Override
    public void ping() {
        keys.listPropertiesOfKeys().iterableByPage().iterator().hasNext();
    }

    // Wrapping

    /**
     * Without a version, wraps on the key's current version through the versionless URL, and records the version the
     * response names: one call, and no race with a rotation between reading the version and wrapping.
     */
    @Override
    public WrappedKey wrapKey(String nativeName, Optional<String> version, byte[] dek) {
        String name = keyName(nativeName);
        var result = client(name, version).wrapKey(KeyWrapAlgorithm.RSA_OAEP_256, dek);
        return wrapped(name, version, result.getKeyId(), result.getEncryptedKey());
    }

    @Override
    public CompletableFuture<WrappedKey> wrapKeyAsync(String nativeName, Optional<String> version, byte[] dek,
                                                     Executor blocking) {
        String name = keyName(nativeName);
        return asyncClient(name, version).wrapKey(KeyWrapAlgorithm.RSA_OAEP_256, dek)
                .map(result -> wrapped(name, version, result.getKeyId(), result.getEncryptedKey()))
                .toFuture();
    }

    private WrappedKey wrapped(String name, Optional<String> version, String kid, byte[] wrapped) {
        String used = version.orElseGet(() -> versionIn(kid, name));
        return new WrappedKey(idPrefix + name, Optional.of(used), WrapAlgorithm.AZURE_RSA_OAEP_256, wrapped);
    }

    /** The version in a {@code kid}: only its last path segment is read, never its host. */
    private String versionIn(String kid, String name) {
        String[] path = kid == null ? new String[0] : kid.split("/");
        if (path.length < 3 || !path[path.length - 3].equals("keys") || !path[path.length - 2].equalsIgnoreCase(name)
                || path[path.length - 1].isEmpty()) {
            throw new UnavailableException("Key Vault didn't say which version of key " + name + " wrapped the data "
                    + "key", null, provider, null);
        }
        return path[path.length - 1];
    }

    /** A failure to unwrap a well-formed request means the wrapped bytes or the entry were changed. */
    @Override
    public byte[] unwrapKey(WrappedKey wrapped) {
        Entry entry = entry(wrapped);
        try {
            return client(entry.name, Optional.of(entry.version))
                    .unwrapKey(KeyWrapAlgorithm.RSA_OAEP_256, wrapped.bytes()).getKey();
        } catch (HttpResponseException e) {
            throw integrity(e, entry.name);
        }
    }

    @Override
    public CompletableFuture<byte[]> unwrapKeyAsync(WrappedKey wrapped, Executor blocking) {
        Entry entry;
        try {
            entry = entry(wrapped);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return asyncClient(entry.name, Optional.of(entry.version))
                .unwrapKey(KeyWrapAlgorithm.RSA_OAEP_256, wrapped.bytes())
                .onErrorMap(HttpResponseException.class, e -> integrity(e, entry.name))
                .map(result -> result.getKey())
                .toFuture();
    }

    private RuntimeException integrity(HttpResponseException e, String name) {
        if (e.getResponse() != null && e.getResponse().getStatusCode() == 400) {
            return new DataIntegrityException("Key Vault couldn't unwrap the data key with key " + name + ": the "
                    + "wrapped key or its entry was changed, or it was wrapped by another key", e, provider, "400");
        }
        return e;
    }

    private record Entry(String name, String version) {
    }

    /** The key's name and version, after checking the entry is this vault's and names a version, as the profile says. */
    private Entry entry(WrappedKey wrapped) {
        if (!inLocation(wrapped.keyId())) {
            throw new InvalidArgumentException("Key " + wrapped.keyId() + " isn't in this store (" + vaultUrl + ")",
                    null, provider, null);
        }
        String version = wrapped.version().orElseThrow(() -> new DataIntegrityException("An azure-kv entry always "
                + "names the key version that wrapped it; this one has none (" + wrapped.keyId() + ")", null, provider,
                null));
        return new Entry(wrapped.keyId().substring(idPrefix.length()), version);
    }

    // Signing

    @Override
    public byte[] signDigest(String nativeName, Optional<String> version, byte[] digest, SigningAlgorithm algorithm) {
        byte[] raw = client(keyName(nativeName), version).sign(signature(algorithm), digest).getSignature();
        return der(algorithm, raw);
    }

    @Override
    public CompletableFuture<byte[]> signDigestAsync(String nativeName, Optional<String> version, byte[] digest,
                                                    SigningAlgorithm algorithm, Executor blocking) {
        return asyncClient(keyName(nativeName), version).sign(signature(algorithm), digest)
                .map(result -> der(algorithm, result.getSignature()))
                .toFuture();
    }

    /** Key Vault's PS256 salt is the digest's length, as Halyx's RSA-PSS expects. */
    private static SignatureAlgorithm signature(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PSS_SHA256 -> SignatureAlgorithm.PS256;
            case RSA_PKCS1_SHA256 -> SignatureAlgorithm.RS256;
            case ECDSA_P256_SHA256 -> SignatureAlgorithm.ES256;
            case ECDSA_P384_SHA384 -> SignatureAlgorithm.ES384;
        };
    }

    /** Key Vault signs ECDSA as JWS does, raw R‖S; Halyx's signatures are DER. RSA signatures pass unchanged. */
    private byte[] der(SigningAlgorithm algorithm, byte[] raw) {
        return switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> raw;
            case ECDSA_P256_SHA256 -> derFromRaw(raw, 32);
            case ECDSA_P384_SHA384 -> derFromRaw(raw, 48);
        };
    }

    /** R‖S, each {@code half} bytes, as the DER {@code SEQUENCE { INTEGER r, INTEGER s }}. */
    static byte[] derFromRaw(byte[] raw, int half) {
        if (raw.length != 2 * half) {
            throw new UnavailableException("Key Vault returned a " + raw.length + "-byte ECDSA signature; expected "
                    + 2 * half, null, AzureProvider.ID, null);
        }
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, half)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, half, raw.length)).toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        integer(body, r);
        integer(body, s);
        ByteArrayOutputStream der = new ByteArrayOutputStream();
        der.write(0x30);
        length(der, body.size());
        der.writeBytes(body.toByteArray());
        return der.toByteArray();
    }

    private static void integer(ByteArrayOutputStream out, byte[] value) {
        out.write(0x02);
        length(out, value.length);
        out.writeBytes(value);
    }

    private static void length(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else {
            out.write(0x81); // P-384 signatures reach 104 bytes at most, so one length byte always suffices
            out.write(length);
        }
    }

    @Override
    public PublicKey publicKey(String nativeName, Optional<String> version) {
        String name = keyName(nativeName);
        return publicKey(name, keys.getKey(name, version.orElse(null)));
    }

    @Override
    public CompletableFuture<PublicKey> publicKeyAsync(String nativeName, Optional<String> version, Executor blocking) {
        String name = keyName(nativeName);
        return asyncKeys.getKey(name, version.orElse(null)).map(key -> publicKey(name, key)).toFuture();
    }

    private PublicKey publicKey(String name, KeyVaultKey key) {
        JsonWebKey jwk = key.getKey();
        KeyType type = jwk.getKeyType();
        if (!isEc(type) && !isRsa(type)) {
            throw new InvalidArgumentException("Key " + name + " (" + type + ") has no public key", null, provider,
                    null);
        }
        try {
            return isEc(type) ? jwk.toEc(false).getPublic() : jwk.toRsa(false).getPublic();
        } catch (RuntimeException e) {
            throw new UnavailableException("Key Vault returned an unreadable public key for key " + name, e, provider,
                    null);
        }
    }

    // Clients

    private CryptographyClient client(String name, Optional<String> version) {
        return clients.computeIfAbsent(keyIdentifier(name, version), id -> builder(id).buildClient());
    }

    private CryptographyAsyncClient asyncClient(String name, Optional<String> version) {
        return asyncClients.computeIfAbsent(keyIdentifier(name, version), id -> builder(id).buildAsyncClient());
    }

    private CryptographyClientBuilder builder(String keyIdentifier) {
        CryptographyClientBuilder builder = cryptography.apply(keyIdentifier).disableKeyCaching();
        return path.isEmpty() ? builder : builder.addPolicy(new VaultPathPolicy(path));
    }

    /**
     * Always the configured vault, never a {@code kid} from a response. A vault URL's path, which only emulators have,
     * is left out here and put back by {@link VaultPathPolicy}: the cryptography client drops it from an identifier.
     */
    private String keyIdentifier(String name, Optional<String> version) {
        return origin + "/keys/" + name + version.map(v -> "/" + v).orElse("");
    }

    // Names, location, capabilities, errors

    /**
     * The Key Vault key name for {@code nativeName}: the name itself, or with native names also the versionless key URL
     * in this vault, as {@link KeyMetadata#nativeId()} gives it.
     */
    String keyName(String nativeName) {
        String name = nativeName;
        if (nativeNames && nativeName.contains("://")) {
            String prefix = vaultUrl + "/keys/";
            if (!nativeName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                throw new InvalidArgumentException("Key " + nativeName + " isn't in this store's vault (" + vaultUrl
                        + ")", null, provider, null);
            }
            name = nativeName.substring(prefix.length());
        }
        if (name.isEmpty() || name.contains("/")) {
            throw new InvalidArgumentException("A Key Vault key is named without a version or path: '" + nativeName
                    + "'", null, provider, null);
        }
        return name;
    }

    /** Whether {@code keyId} names a key in this vault: the vault's host is compared without regard to case. */
    @Override
    public boolean inLocation(String keyId) {
        return keyId.regionMatches(true, 0, idPrefix, 0, idPrefix.length()) && keyId.length() > idPrefix.length()
                && keyId.indexOf('/', idPrefix.length()) < 0;
    }

    @Override
    public boolean unwraps(WrapAlgorithm algorithm) {
        return algorithm == WrapAlgorithm.AZURE_RSA_OAEP_256;
    }

    /** No MACs and no server-side rewrap: Key Vault offers them only on Managed HSM, which Halyx doesn't support yet. */
    @Override
    public Capabilities capabilities() {
        return Capabilities.none();
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return AzureErrors.translate(cause);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(keys, asyncKeys);
    }

    @Override
    public String toString() {
        return "KeyVaultKeyDriver[" + vaultUrl + "]";
    }

    // Key Vault's key descriptions in Halyx's terms

    /**
     * An RSA key wraps when it allows {@code wrapKey} and {@code unwrapKey}, and signs when it allows {@code sign};
     * many RSA keys do both, and {@code KeyOptions.purpose} picks one. EC keys sign. A key listing no operations
     * allows them all, as Key Vault does. {@code oct} keys exist only on Managed HSM, which Halyx doesn't support yet.
     */
    static Set<KeyPurpose> purposes(JsonWebKey jwk) {
        KeyType type = jwk.getKeyType();
        List<KeyOperation> ops = jwk.getKeyOps();
        boolean all = ops == null || ops.isEmpty();
        Set<KeyPurpose> purposes = EnumSet.noneOf(KeyPurpose.class);
        if (isRsa(type) && (all || ops.contains(KeyOperation.WRAP_KEY) && ops.contains(KeyOperation.UNWRAP_KEY))) {
            purposes.add(KeyPurpose.ENCRYPTION);
        }
        if ((isRsa(type) || isEc(type) && curve(jwk) != null) && (all || ops.contains(KeyOperation.SIGN))) {
            purposes.add(KeyPurpose.SIGNING);
        }
        return purposes;
    }

    static Set<SigningAlgorithm> algorithms(JsonWebKey jwk) {
        if (isRsa(jwk.getKeyType())) {
            return Set.of(SigningAlgorithm.RSA_PSS_SHA256, SigningAlgorithm.RSA_PKCS1_SHA256);
        }
        String curve = curve(jwk);
        return "P-256".equals(curve) ? Set.of(SigningAlgorithm.ECDSA_P256_SHA256)
                : "P-384".equals(curve) ? Set.of(SigningAlgorithm.ECDSA_P384_SHA384) : Set.of();
    }

    /** The key in Halyx's names: {@code RSA_2048} from the modulus, {@code EC_P256} from the curve, else Azure's type. */
    static String keyType(JsonWebKey jwk) {
        KeyType type = jwk.getKeyType();
        if (isRsa(type) && jwk.getN() != null) {
            return "RSA_" + new BigInteger(1, jwk.getN()).bitLength();
        }
        if (isEc(type) && jwk.getCurveName() != null) {
            return "EC_" + jwk.getCurveName().toString().replace("-", "");
        }
        return String.valueOf(type).toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** P-256 and P-384, the curves Halyx signs with; null for any other. */
    private static String curve(JsonWebKey jwk) {
        KeyCurveName curve = jwk.getCurveName();
        if (KeyCurveName.P_256.equals(curve)) {
            return "P-256";
        }
        return KeyCurveName.P_384.equals(curve) ? "P-384" : null;
    }

    private static boolean isRsa(KeyType type) {
        return KeyType.RSA.equals(type) || KeyType.RSA_HSM.equals(type);
    }

    private static boolean isEc(KeyType type) {
        return KeyType.EC.equals(type) || KeyType.EC_HSM.equals(type);
    }
}
