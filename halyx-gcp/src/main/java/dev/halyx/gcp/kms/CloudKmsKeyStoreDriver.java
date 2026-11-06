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
package dev.halyx.gcp.kms;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionState;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.GetCryptoKeyRequest;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.ListCryptoKeysRequest;
import com.google.cloud.kms.v1.MacSignRequest;
import com.google.cloud.kms.v1.MacSignResponse;
import com.google.cloud.kms.v1.MacVerifyRequest;
import com.google.cloud.kms.v1.MacVerifyResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.CloudException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnavailableException;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpFutures;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.keys.KeyCapability;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import dev.halyx.keys.spi.AbstractKeyStoreDriver;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Keys in one Google Cloud KMS key ring, the store's location ({@code projects/acme/locations/global/keyRings/orders}).
 * A key's name is the crypto key's id; with native names, the full crypto key name in this key ring is accepted too.
 * Data keys are wrapped with {@code Encrypt} on an {@code ENCRYPT_DECRYPT} key, as the HLXS {@code gcp-kms} profile
 * defines, and each entry records the version that wrapped it. Every request and response carries a CRC32C, as Google
 * recommends: a mismatch either way is a {@link DataIntegrityException}.
 *
 * <p>Symmetric keys have a primary version, which encrypts. Signing and MAC keys have none, so their current version
 * is the newest enabled one. Every call except {@code list} has a non-blocking path.
 */
public final class CloudKmsKeyStoreDriver extends AbstractKeyStoreDriver {

    static final String SCHEME = "gcp-kms";

    private static final Pattern KEY_RING = Pattern.compile("projects/[^/]+/locations/[^/]+/keyRings/[^/]+");
    private static final Pattern RSA_SIZE = Pattern.compile("_(\\d{4})_");
    private static final Capabilities CAPABILITIES = Capabilities.of(KeyCapability.MAC);

    private final KeyManagementServiceClient kms;
    private final String keyRing;
    private final String keyPrefix;
    private final String idPrefix;
    private final boolean nativeNames;

    /** @param keyRing the key ring's full name, the store's location */
    public CloudKmsKeyStoreDriver(KeyManagementServiceClient kms, String keyRing, boolean nativeNames) {
        super(GcpProvider.ID);
        String ring = keyRing.trim();
        while (ring.endsWith("/")) {
            ring = ring.substring(0, ring.length() - 1);
        }
        if (!KEY_RING.matcher(ring).matches()) {
            throw new IllegalArgumentException("A Cloud KMS key store's location is its key ring, such as "
                    + "projects/acme/locations/global/keyRings/orders, not '" + keyRing + "'");
        }
        this.kms = kms;
        this.keyRing = ring;
        this.keyPrefix = ring + "/cryptoKeys/";
        this.idPrefix = SCHEME + ":" + keyPrefix;
        this.nativeNames = nativeNames;
    }

    // Metadata

    @Override
    public KeyMetadata metadata(String nativeName) {
        String key = keyNameOf(nativeName);
        List<CryptoKeyVersion> versions = new ArrayList<>();
        kms.listCryptoKeyVersions(ListCryptoKeyVersionsRequest.newBuilder().setParent(key).build()).iterateAll()
                .forEach(versions::add);
        return metadata(nativeName, kms.getCryptoKey(key), versions);
    }

    @Override
    public CompletableFuture<KeyMetadata> metadataAsync(String nativeName, Executor blocking) {
        String key = keyNameOf(nativeName);
        CompletableFuture<CryptoKey> cryptoKey = GcpFutures.of(kms.getCryptoKeyCallable().futureCall(
                GetCryptoKeyRequest.newBuilder().setName(key).build()));
        CompletableFuture<List<CryptoKeyVersion>> versions = versionsAsync(key);
        return cryptoKey.thenCombine(versions, (k, v) -> metadata(nativeName, k, v));
    }

    private CompletableFuture<List<CryptoKeyVersion>> versionsAsync(String key) {
        return GcpFutures.of(kms.listCryptoKeyVersionsPagedCallable().futureCall(
                        ListCryptoKeyVersionsRequest.newBuilder().setParent(key).build()))
                .thenCompose(response -> GcpFutures.allPages(response.getPage()));
    }

    private KeyMetadata metadata(String nativeName, CryptoKey key, List<CryptoKeyVersion> versions) {
        Optional<CryptoKeyVersion> current = current(key, versions);
        CryptoKeyVersionAlgorithm algorithm = current.map(CryptoKeyVersion::getAlgorithm)
                .orElse(key.getVersionTemplate().getAlgorithm());
        Set<KeyPurpose> purposes = purposes(key.getPurpose(), algorithm);
        return new KeyMetadata(nativeName, SCHEME + ":" + key.getName(), key.getName(), purposes, keyType(algorithm),
                purposes.contains(KeyPurpose.SIGNING) ? signing(algorithm).map(Set::of).orElse(Set.of()) : Set.of(),
                current.map(v -> v.getState() == CryptoKeyVersionState.ENABLED).orElse(false),
                current.map(v -> versionId(v.getName())), enabled(versions));
    }

    /**
     * The version new operations use: the primary for a symmetric key; for keys without one, the newest enabled
     * version, or the newest of all when none is enabled, so a disabled key still reports itself disabled.
     */
    static Optional<CryptoKeyVersion> current(CryptoKey key, List<CryptoKeyVersion> versions) {
        if (key.getPurpose() == CryptoKeyPurpose.ENCRYPT_DECRYPT && key.hasPrimary()) {
            String primary = key.getPrimary().getName();
            return Optional.of(versions.stream().filter(v -> v.getName().equals(primary)).findFirst()
                    .orElse(key.getPrimary()));
        }
        Comparator<CryptoKeyVersion> byNumber = Comparator.comparingLong(v -> number(v.getName()));
        return versions.stream().filter(v -> v.getState() == CryptoKeyVersionState.ENABLED).max(byNumber)
                .or(() -> versions.stream().max(byNumber));
    }

    /** The enabled versions' ids, oldest first. */
    static List<String> enabled(List<CryptoKeyVersion> versions) {
        return versions.stream().filter(v -> v.getState() == CryptoKeyVersionState.ENABLED)
                .sorted(Comparator.comparingLong(v -> number(v.getName())))
                .map(v -> versionId(v.getName())).toList();
    }

    @Override
    public List<KeyMetadata> list(String nativePrefix) {
        List<KeyMetadata> found = new ArrayList<>();
        // iterateAll follows every nextPageToken, whatever page size the service chose.
        for (CryptoKey key : kms.listCryptoKeys(ListCryptoKeysRequest.newBuilder().setParent(keyRing).build())
                .iterateAll()) {
            String id = key.getName().substring(key.getName().lastIndexOf('/') + 1);
            String name = nativeNames && nativePrefix.startsWith("projects/") ? key.getName() : id;
            if (!name.startsWith(nativePrefix)) {
                continue;
            }
            try {
                found.add(metadata(name));
            } catch (ApiException e) {
                if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                    throw e;
                }
                // deleted since the listing: Cloud KMS keys can't be deleted, but their ring's IAM can change
            }
        }
        found.sort(Comparator.comparing(KeyMetadata::name));
        return found;
    }

    @Override
    public void ping() {
        kms.listCryptoKeys(ListCryptoKeysRequest.newBuilder().setParent(keyRing).setPageSize(1).build())
                .getPage();
    }

    // Wrapping

    @Override
    public WrappedKey wrapKey(String nativeName, Optional<String> version, byte[] dek) {
        String key = keyNameOf(nativeName);
        EncryptRequest request = encrypt(key, version, dek);
        return wrapped(key, request, kms.encrypt(request));
    }

    @Override
    public CompletableFuture<WrappedKey> wrapKeyAsync(String nativeName, Optional<String> version, byte[] dek,
                                                     Executor blocking) {
        String key = keyNameOf(nativeName);
        EncryptRequest request = encrypt(key, version, dek);
        return GcpFutures.of(kms.encryptCallable().futureCall(request))
                .thenApply(response -> wrapped(key, request, response));
    }

    /** On the crypto key, Cloud KMS encrypts with its primary version; a pinned version is named instead. */
    private static EncryptRequest encrypt(String key, Optional<String> version, byte[] dek) {
        ByteString plaintext = ByteString.copyFrom(dek);
        return EncryptRequest.newBuilder()
                .setName(version.map(v -> key + "/cryptoKeyVersions/" + v).orElse(key))
                .setPlaintext(plaintext)
                .setPlaintextCrc32C(crc(plaintext))
                .build();
    }

    private WrappedKey wrapped(String key, EncryptRequest request, EncryptResponse response) {
        if (!response.getVerifiedPlaintextCrc32C()) {
            throw integrity("Cloud KMS didn't verify the data key's checksum for key " + key);
        }
        check(response.getCiphertext(), response.hasCiphertextCrc32C() ? response.getCiphertextCrc32C() : null,
                "wrapped key", key);
        if (!response.getName().startsWith(key + "/cryptoKeyVersions/")) {
            throw new UnavailableException("Cloud KMS encrypted with an unexpected key (" + response.getName()
                    + ") for key " + key, null, provider, null);
        }
        return new WrappedKey(SCHEME + ":" + key, Optional.of(versionId(response.getName())), WrapAlgorithm.GCP_KMS,
                response.getCiphertext().toByteArray());
    }

    /** {@code Decrypt} on the crypto key: the ciphertext names its version. Refused ciphertext is an integrity error. */
    @Override
    public byte[] unwrapKey(WrappedKey wrapped) {
        String key = keyIn(wrapped);
        DecryptRequest request = decrypt(key, wrapped);
        try {
            return plaintext(key, kms.decrypt(request));
        } catch (ApiException e) {
            throw refused(e, key);
        }
    }

    @Override
    public CompletableFuture<byte[]> unwrapKeyAsync(WrappedKey wrapped, Executor blocking) {
        String key;
        DecryptRequest request;
        try {
            key = keyIn(wrapped);
            request = decrypt(key, wrapped);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return GcpFutures.of(kms.decryptCallable().futureCall(request))
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = unwrapFuture(error);
                        throw new CompletionException(cause instanceof ApiException e ? refused(e, key) : cause);
                    }
                    return plaintext(key, response);
                });
    }

    private static DecryptRequest decrypt(String key, WrappedKey wrapped) {
        ByteString ciphertext = ByteString.copyFrom(wrapped.bytes());
        return DecryptRequest.newBuilder().setName(key).setCiphertext(ciphertext).setCiphertextCrc32C(crc(ciphertext))
                .build();
    }

    private byte[] plaintext(String key, DecryptResponse response) {
        check(response.getPlaintext(), response.hasPlaintextCrc32C() ? response.getPlaintextCrc32C() : null,
                "data key", key);
        return response.getPlaintext().toByteArray();
    }

    /** {@code INVALID_ARGUMENT} from {@code Decrypt} means the ciphertext isn't this key's, or was changed. */
    private RuntimeException refused(ApiException e, String key) {
        if (e.getStatusCode().getCode() == StatusCode.Code.INVALID_ARGUMENT) {
            return new DataIntegrityException("Cloud KMS couldn't unwrap the data key with key " + key + ": the "
                    + "wrapped key was changed, or another key wrapped it", e, provider, "INVALID_ARGUMENT");
        }
        return e;
    }

    /** The crypto key of an entry in this key ring. */
    private String keyIn(WrappedKey wrapped) {
        if (!inLocation(wrapped.keyId())) {
            throw new InvalidArgumentException("Key " + wrapped.keyId() + " isn't in this store (" + keyRing + ")",
                    null, provider, null);
        }
        return wrapped.keyId().substring(SCHEME.length() + 1);
    }

    // Signing

    @Override
    public byte[] signDigest(String nativeName, Optional<String> version, byte[] digest, SigningAlgorithm algorithm) {
        String key = keyNameOf(nativeName);
        String name = versionName(key, version.orElseGet(() -> currentVersion(key)));
        AsymmetricSignRequest request = sign(name, digest, algorithm);
        return signature(name, kms.asymmetricSign(request));
    }

    @Override
    public CompletableFuture<byte[]> signDigestAsync(String nativeName, Optional<String> version, byte[] digest,
                                                    SigningAlgorithm algorithm, Executor blocking) {
        String key = keyNameOf(nativeName);
        return versionAsync(key, version, blocking).thenCompose(v -> {
            String name = versionName(key, v);
            return GcpFutures.of(kms.asymmetricSignCallable().futureCall(sign(name, digest, algorithm)))
                    .thenApply(response -> signature(name, response));
        });
    }

    private static AsymmetricSignRequest sign(String version, byte[] digest, SigningAlgorithm algorithm) {
        ByteString bytes = ByteString.copyFrom(digest);
        Digest.Builder d = Digest.newBuilder();
        if (algorithm == SigningAlgorithm.ECDSA_P384_SHA384) {
            d.setSha384(bytes);
        } else {
            d.setSha256(bytes);
        }
        return AsymmetricSignRequest.newBuilder().setName(version).setDigest(d).setDigestCrc32C(crc(bytes)).build();
    }

    /** Cloud KMS signs ECDSA in DER and RSA as PKCS #1 defines, as Halyx's signatures are, so nothing is converted. */
    private byte[] signature(String version, AsymmetricSignResponse response) {
        if (!response.getVerifiedDigestCrc32C()) {
            throw integrity("Cloud KMS didn't verify the digest's checksum for key version " + version);
        }
        if (!response.getName().isEmpty() && !response.getName().equals(version)) {
            throw new UnavailableException("Cloud KMS signed with " + response.getName() + ", not " + version, null,
                    provider, null);
        }
        check(response.getSignature(), response.hasSignatureCrc32C() ? response.getSignatureCrc32C() : null,
                "signature", version);
        return response.getSignature().toByteArray();
    }

    @Override
    public PublicKey publicKey(String nativeName, Optional<String> version) {
        String key = keyNameOf(nativeName);
        String name = versionName(key, version.orElseGet(() -> currentVersion(key)));
        return publicKey(name, kms.getPublicKey(name));
    }

    @Override
    public CompletableFuture<PublicKey> publicKeyAsync(String nativeName, Optional<String> version, Executor blocking) {
        String key = keyNameOf(nativeName);
        return versionAsync(key, version, blocking).thenCompose(v -> {
            String name = versionName(key, v);
            return GcpFutures.of(kms.getPublicKeyCallable().futureCall(
                            GetPublicKeyRequest.newBuilder().setName(name).build()))
                    .thenApply(response -> publicKey(name, response));
        });
    }

    private PublicKey publicKey(String version, com.google.cloud.kms.v1.PublicKey response) {
        check(ByteString.copyFromUtf8(response.getPem()), response.hasPemCrc32C() ? response.getPemCrc32C() : null,
                "public key", version);
        String base64 = response.getPem().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(base64));
            boolean ec = response.getAlgorithm().name().startsWith("EC_");
            return KeyFactory.getInstance(ec ? "EC" : "RSA").generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new UnavailableException("Cloud KMS returned an unreadable public key for " + version, e, provider,
                    null);
        }
    }

    // MACs

    /** {@code MacSign} needs a version: the key's newest enabled one. */
    @Override
    public byte[] generateMac(String nativeName, byte[] data) {
        String key = keyNameOf(nativeName);
        MacSignRequest request = macSign(versionName(key, currentVersion(key)), data);
        return mac(request.getName(), kms.macSign(request));
    }

    @Override
    public CompletableFuture<byte[]> generateMacAsync(String nativeName, byte[] data, Executor blocking) {
        String key = keyNameOf(nativeName);
        return versionAsync(key, Optional.empty(), blocking).thenCompose(v -> {
            MacSignRequest request = macSign(versionName(key, v), data);
            return GcpFutures.of(kms.macSignCallable().futureCall(request))
                    .thenApply(response -> mac(request.getName(), response));
        });
    }

    private static MacSignRequest macSign(String version, byte[] data) {
        ByteString bytes = ByteString.copyFrom(data);
        return MacSignRequest.newBuilder().setName(version).setData(bytes).setDataCrc32C(crc(bytes)).build();
    }

    private byte[] mac(String version, MacSignResponse response) {
        if (!response.getVerifiedDataCrc32C()) {
            throw integrity("Cloud KMS didn't verify the data's checksum for key version " + version);
        }
        check(response.getMac(), response.hasMacCrc32C() ? response.getMacCrc32C() : null, "MAC", version);
        return response.getMac().toByteArray();
    }

    /**
     * Checks {@code mac} against each enabled version, newest first: a MAC doesn't say which version made it, and
     * MACs made before a rotation still verify.
     */
    @Override
    public boolean verifyMac(String nativeName, byte[] data, byte[] mac) {
        String key = keyNameOf(nativeName);
        List<String> versions = new ArrayList<>(enabled(versions(key)));
        Collections.reverse(versions);
        for (String version : versions) {
            MacVerifyRequest request = macVerify(versionName(key, version), data, mac);
            if (verified(request.getName(), kms.macVerify(request))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Boolean> verifyMacAsync(String nativeName, byte[] data, byte[] mac, Executor blocking) {
        String key = keyNameOf(nativeName);
        return versionsAsync(key).thenCompose(all -> {
            List<String> versions = new ArrayList<>(enabled(all));
            Collections.reverse(versions);
            return verifyFrom(key, data, mac, versions, 0);
        });
    }

    private CompletableFuture<Boolean> verifyFrom(String key, byte[] data, byte[] mac, List<String> versions, int i) {
        if (i == versions.size()) {
            return CompletableFuture.completedFuture(false);
        }
        MacVerifyRequest request = macVerify(versionName(key, versions.get(i)), data, mac);
        return GcpFutures.of(kms.macVerifyCallable().futureCall(request))
                .thenCompose(response -> verified(request.getName(), response)
                        ? CompletableFuture.completedFuture(true) : verifyFrom(key, data, mac, versions, i + 1));
    }

    private static MacVerifyRequest macVerify(String version, byte[] data, byte[] mac) {
        ByteString d = ByteString.copyFrom(data);
        ByteString m = ByteString.copyFrom(mac);
        return MacVerifyRequest.newBuilder().setName(version).setData(d).setDataCrc32C(crc(d)).setMac(m)
                .setMacCrc32C(crc(m)).build();
    }

    /** {@code verifiedSuccessIntegrity} confirms the answer wasn't corrupted on its way back. */
    private boolean verified(String version, MacVerifyResponse response) {
        if (!response.getVerifiedDataCrc32C() || !response.getVerifiedMacCrc32C()) {
            throw integrity("Cloud KMS didn't verify the checksums of a MAC check for key version " + version);
        }
        if (response.getSuccess() != response.getVerifiedSuccessIntegrity()) {
            throw integrity("Cloud KMS's MAC check for key version " + version + " failed its integrity check");
        }
        return response.getSuccess();
    }

    // Versions

    private List<CryptoKeyVersion> versions(String key) {
        List<CryptoKeyVersion> versions = new ArrayList<>();
        kms.listCryptoKeyVersions(ListCryptoKeyVersionsRequest.newBuilder().setParent(key).build()).iterateAll()
                .forEach(versions::add);
        return versions;
    }

    /** The newest enabled version of a key without a primary; a key with none enabled is refused. */
    private String currentVersion(String key) {
        return newestEnabled(key, versions(key));
    }

    private CompletableFuture<String> versionAsync(String key, Optional<String> version, Executor blocking) {
        return version.map(CompletableFuture::completedFuture)
                .orElseGet(() -> versionsAsync(key).thenApply(all -> newestEnabled(key, all)));
    }

    private String newestEnabled(String key, List<CryptoKeyVersion> versions) {
        List<String> enabled = enabled(versions);
        if (enabled.isEmpty()) {
            throw new AccessDeniedException("Key " + key + " has no enabled version", null, provider,
                    "FAILED_PRECONDITION");
        }
        return enabled.get(enabled.size() - 1);
    }

    // Names, location, capabilities, errors

    /**
     * The full crypto key name for {@code nativeName}: the key ring plus the id, or with native names a full crypto
     * key name in this key ring, as {@link KeyMetadata#nativeId()} gives it.
     */
    String keyNameOf(String nativeName) {
        String id = nativeName;
        if (nativeNames && nativeName.startsWith("projects/")) {
            if (!nativeName.startsWith(keyPrefix)) {
                throw new InvalidArgumentException("Key " + nativeName + " isn't in this store's key ring ("
                        + keyRing + ")", null, provider, null);
            }
            id = nativeName.substring(keyPrefix.length());
        }
        if (id.isEmpty() || id.contains("/")) {
            throw new InvalidArgumentException("A Cloud KMS crypto key is named without a version or path: '"
                    + nativeName + "'", null, provider, null);
        }
        return keyPrefix + id;
    }

    private static String versionName(String key, String version) {
        return key + "/cryptoKeyVersions/" + version;
    }

    static String versionId(String versionName) {
        return versionName.substring(versionName.lastIndexOf('/') + 1);
    }

    private static long number(String versionName) {
        try {
            return Long.parseLong(versionId(versionName));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public boolean inLocation(String keyId) {
        return keyId.startsWith(idPrefix) && keyId.length() > idPrefix.length()
                && keyId.indexOf('/', idPrefix.length()) < 0;
    }

    @Override
    public boolean unwraps(WrapAlgorithm algorithm) {
        return algorithm == WrapAlgorithm.GCP_KMS;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * Cloud KMS reports a disabled or destroyed version with {@code FAILED_PRECONDITION}, which is a refusal of the
     * key, not a bad request; a request whose checksum fails is {@code INVALID_ARGUMENT} saying so.
     */
    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        Throwable cause = unwrapFuture(error);
        if (cause instanceof ApiException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            StatusCode.Code code = e.getStatusCode().getCode();
            if (code == StatusCode.Code.FAILED_PRECONDITION) {
                return Optional.of(new AccessDeniedException(message, e, provider, code.name()));
            }
            if (code == StatusCode.Code.INVALID_ARGUMENT && message.toLowerCase(Locale.ROOT).contains("checksum")) {
                return Optional.of(new DataIntegrityException(message, e, provider, code.name()));
            }
        }
        return GcpErrors.translate(cause);
    }

    private static Throwable unwrapFuture(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(kms);
    }

    @Override
    public String toString() {
        return "CloudKmsKeyStoreDriver[" + keyRing + "]";
    }

    // Checksums

    static Int64Value crc(ByteString bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes.asReadOnlyByteBuffer());
        return Int64Value.of(crc.getValue());
    }

    /** A response's checksum must be present and match: Google recommends checking both, and D3 makes it required. */
    private void check(ByteString bytes, Int64Value expected, String what, String key) {
        if (expected == null || expected.getValue() != crc(bytes).getValue()) {
            throw integrity("The " + what + " from Cloud KMS for " + key + " failed its CRC32C check");
        }
    }

    private DataIntegrityException integrity(String message) {
        return new DataIntegrityException(message, null, provider, "CRC32C");
    }

    // Cloud KMS's key descriptions in Halyx's terms

    static Set<KeyPurpose> purposes(CryptoKeyPurpose purpose, CryptoKeyVersionAlgorithm algorithm) {
        return switch (purpose) {
            case ENCRYPT_DECRYPT -> Set.of(KeyPurpose.ENCRYPTION);
            case ASYMMETRIC_SIGN -> signing(algorithm).isPresent() ? Set.of(KeyPurpose.SIGNING) : Set.of();
            case MAC -> algorithm == CryptoKeyVersionAlgorithm.HMAC_SHA256 ? Set.of(KeyPurpose.MAC) : Set.of();
            default -> Set.of();
        };
    }

    /** A Cloud KMS signing version has exactly one algorithm, padding included. */
    static Optional<SigningAlgorithm> signing(CryptoKeyVersionAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_SIGN_PSS_2048_SHA256, RSA_SIGN_PSS_3072_SHA256, RSA_SIGN_PSS_4096_SHA256 ->
                    Optional.of(SigningAlgorithm.RSA_PSS_SHA256);
            case RSA_SIGN_PKCS1_2048_SHA256, RSA_SIGN_PKCS1_3072_SHA256, RSA_SIGN_PKCS1_4096_SHA256 ->
                    Optional.of(SigningAlgorithm.RSA_PKCS1_SHA256);
            case EC_SIGN_P256_SHA256 -> Optional.of(SigningAlgorithm.ECDSA_P256_SHA256);
            case EC_SIGN_P384_SHA384 -> Optional.of(SigningAlgorithm.ECDSA_P384_SHA384);
            default -> Optional.empty();
        };
    }

    /** {@code AES_256}, {@code RSA_2048}, {@code EC_P256}, {@code HMAC_256}; else Cloud KMS's own name. */
    static String keyType(CryptoKeyVersionAlgorithm algorithm) {
        String name = algorithm.name();
        if (algorithm == CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION) {
            return "AES_256";
        }
        Matcher size = RSA_SIZE.matcher(name);
        if (name.startsWith("RSA_SIGN_") && size.find()) {
            return "RSA_" + size.group(1); // RSA_SIGN_PKCS1_2048_SHA256: the size, not PKCS1's 1
        }
        if (name.startsWith("EC_SIGN_P256")) {
            return "EC_P256";
        }
        if (name.startsWith("EC_SIGN_P384")) {
            return "EC_P384";
        }
        if (name.startsWith("HMAC_SHA")) {
            return "HMAC_" + name.substring("HMAC_SHA".length());
        }
        return name;
    }
}
