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
package dev.halyx.aws.kms;

import dev.halyx.aws.AwsErrors;
import dev.halyx.aws.AwsProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.CloudException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.keys.KeyCapability;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import dev.halyx.keys.spi.AbstractKeyStoreDriver;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.AliasListEntry;
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse;
import software.amazon.awssdk.services.kms.model.EncryptionAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KeyState;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.ListAliasesResponse;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Keys on AWS KMS, in the provider's account and region. A portable name is an alias without {@code alias/}, so
 * {@code orders-data} is {@code alias/orders-data}; with native names, a key ID, key ARN, alias or alias ARN is used as
 * given. Data keys are wrapped with {@code Encrypt} on a symmetric key, as the HLXS {@code aws-kms} profile defines,
 * and the key id written into sealed data is the key ARN, which stays valid when aliases change.
 *
 * <p>KMS rotates symmetric keys inside the key and never rotates asymmetric ones, so keys report no versions. Every call
 * except {@code list} has a non-blocking path on the asynchronous client.
 */
public final class KmsKeyStoreDriver extends AbstractKeyStoreDriver {

    static final String SCHEME = "aws-kms";

    private static final String ALIAS = "alias/";
    private static final String MANAGED = "alias/aws/";
    private static final Capabilities CAPABILITIES = Capabilities.of(KeyCapability.MAC,
            KeyCapability.SERVER_SIDE_REWRAP);

    private final KmsClient kms;
    private final Supplier<KmsAsyncClient> kmsAsync;
    private final String region;
    private final boolean nativeNames;

    /**
     * @param region   the region the clients call, which is the store's location
     * @param kmsAsync the non-blocking client for asynchronous calls, made when first asked for
     */
    public KmsKeyStoreDriver(KmsClient kms, String region, boolean nativeNames, Supplier<KmsAsyncClient> kmsAsync) {
        super(AwsProvider.ID);
        this.kms = kms;
        this.region = region;
        this.nativeNames = nativeNames;
        this.kmsAsync = kmsAsync;
    }

    // Metadata

    @Override
    public KeyMetadata metadata(String nativeName) {
        return metadata(nativeName, kms.describeKey(b -> b.keyId(keyIdOf(nativeName))));
    }

    @Override
    public CompletableFuture<KeyMetadata> metadataAsync(String nativeName, Executor blocking) {
        return kmsAsync.get().describeKey(b -> b.keyId(keyIdOf(nativeName)))
                .thenApply(response -> metadata(nativeName, response));
    }

    private KeyMetadata metadata(String name, DescribeKeyResponse response) {
        var key = response.keyMetadata();
        String spec = key.keySpecAsString();
        Set<KeyPurpose> purposes = purposes(key.keyUsage(), spec, key.macAlgorithms());
        return new KeyMetadata(name, SCHEME + ":" + key.arn(), key.arn(), purposes, portableType(spec),
                purposes.contains(KeyPurpose.SIGNING) ? algorithms(spec, key.signingAlgorithms()) : Set.of(),
                key.keyState() == KeyState.ENABLED, Optional.empty(), List.of());
    }

    /**
     * Pages through the aliases by {@code Marker}, skipping the AWS-managed {@code alias/aws/*} ones and aliases with no
     * key. Keys without an alias aren't listed: an alias is what a key's name is.
     */
    @Override
    public List<KeyMetadata> list(String nativePrefix) {
        List<KeyMetadata> found = new ArrayList<>();
        String marker = null;
        do {
            String from = marker;
            ListAliasesResponse page = kms.listAliases(b -> b.marker(from));
            for (AliasListEntry alias : page.aliases()) {
                String aliasName = alias.aliasName();
                if (aliasName == null || aliasName.startsWith(MANAGED) || alias.targetKeyId() == null) {
                    continue;
                }
                String name = nativeNames ? aliasName : aliasName.substring(ALIAS.length());
                if (!name.startsWith(nativePrefix)) {
                    continue;
                }
                try {
                    found.add(metadata(name));
                } catch (software.amazon.awssdk.services.kms.model.NotFoundException e) {
                    // deleted since the listing
                }
            }
            marker = Boolean.TRUE.equals(page.truncated()) ? page.nextMarker() : null;
        } while (marker != null);
        found.sort(Comparator.comparing(KeyMetadata::name));
        return found;
    }

    @Override
    public void ping() {
        kms.listAliases(b -> b.limit(1));
    }

    // Wrapping

    @Override
    public WrappedKey wrapKey(String nativeName, Optional<String> version, byte[] dek) {
        noVersion(nativeName, version);
        var response = kms.encrypt(b -> b.keyId(keyIdOf(nativeName)).plaintext(SdkBytes.fromByteArray(dek))
                .encryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT));
        return wrapped(response.keyId(), response.ciphertextBlob());
    }

    @Override
    public CompletableFuture<WrappedKey> wrapKeyAsync(String nativeName, Optional<String> version, byte[] dek,
                                                     Executor blocking) {
        noVersion(nativeName, version);
        return kmsAsync.get().encrypt(b -> b.keyId(keyIdOf(nativeName)).plaintext(SdkBytes.fromByteArray(dek))
                        .encryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT))
                .thenApply(response -> wrapped(response.keyId(), response.ciphertextBlob()));
    }

    /** The response's {@code KeyId} is the key ARN, whatever named the key in the request. */
    private static WrappedKey wrapped(String arn, SdkBytes ciphertext) {
        return new WrappedKey(SCHEME + ":" + arn, Optional.empty(), WrapAlgorithm.AWS_KMS, ciphertext.asByteArray());
    }

    @Override
    public byte[] unwrapKey(WrappedKey wrapped) {
        String arn = arnIn(wrapped);
        return kms.decrypt(b -> b.keyId(arn).ciphertextBlob(SdkBytes.fromByteArray(wrapped.bytes()))
                .encryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT)).plaintext().asByteArray();
    }

    @Override
    public CompletableFuture<byte[]> unwrapKeyAsync(WrappedKey wrapped, Executor blocking) {
        String arn;
        try {
            arn = arnIn(wrapped);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return kmsAsync.get().decrypt(b -> b.keyId(arn).ciphertextBlob(SdkBytes.fromByteArray(wrapped.bytes()))
                        .encryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT))
                .thenApply(response -> response.plaintext().asByteArray());
    }

    /**
     * Rewraps inside KMS with {@code ReEncrypt}, so the data key never leaves it. Naming the source key ARN makes KMS
     * refuse a ciphertext from any other key, as {@code Decrypt} does.
     */
    @Override
    public WrappedKey rewrapKey(WrappedKey wrapped, String targetNativeName) {
        String arn = arnIn(wrapped);
        var response = kms.reEncrypt(b -> b.ciphertextBlob(SdkBytes.fromByteArray(wrapped.bytes()))
                .sourceKeyId(arn).sourceEncryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT)
                .destinationKeyId(keyIdOf(targetNativeName))
                .destinationEncryptionAlgorithm(EncryptionAlgorithmSpec.SYMMETRIC_DEFAULT));
        return wrapped(response.keyId(), response.ciphertextBlob());
    }

    /** The key ARN of an entry in this store. */
    private String arnIn(WrappedKey wrapped) {
        if (!inLocation(wrapped.keyId())) {
            throw new InvalidArgumentException("Key " + wrapped.keyId() + " isn't in this store (AWS KMS in " + region
                    + ")", null, provider, null);
        }
        return wrapped.keyId().substring(SCHEME.length() + 1);
    }

    // Signing

    @Override
    public byte[] signDigest(String nativeName, Optional<String> version, byte[] digest, SigningAlgorithm algorithm) {
        noVersion(nativeName, version);
        return kms.sign(b -> b.keyId(keyIdOf(nativeName)).message(SdkBytes.fromByteArray(digest))
                .messageType(MessageType.DIGEST).signingAlgorithm(spec(algorithm))).signature().asByteArray();
    }

    @Override
    public CompletableFuture<byte[]> signDigestAsync(String nativeName, Optional<String> version, byte[] digest,
                                                    SigningAlgorithm algorithm, Executor blocking) {
        noVersion(nativeName, version);
        return kmsAsync.get().sign(b -> b.keyId(keyIdOf(nativeName)).message(SdkBytes.fromByteArray(digest))
                        .messageType(MessageType.DIGEST).signingAlgorithm(spec(algorithm)))
                .thenApply(response -> response.signature().asByteArray());
    }

    /** KMS's ECDSA signatures are already DER, and its PSS salt is the digest's length, as Halyx's PS256 expects. */
    private static SigningAlgorithmSpec spec(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PSS_SHA256 -> SigningAlgorithmSpec.RSASSA_PSS_SHA_256;
            case RSA_PKCS1_SHA256 -> SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256;
            case ECDSA_P256_SHA256 -> SigningAlgorithmSpec.ECDSA_SHA_256;
            case ECDSA_P384_SHA384 -> SigningAlgorithmSpec.ECDSA_SHA_384;
        };
    }

    @Override
    public PublicKey publicKey(String nativeName, Optional<String> version) {
        noVersion(nativeName, version);
        return publicKey(nativeName, kms.getPublicKey(b -> b.keyId(keyIdOf(nativeName))));
    }

    @Override
    public CompletableFuture<PublicKey> publicKeyAsync(String nativeName, Optional<String> version, Executor blocking) {
        noVersion(nativeName, version);
        return kmsAsync.get().getPublicKey(b -> b.keyId(keyIdOf(nativeName)))
                .thenApply(response -> publicKey(nativeName, response));
    }

    private PublicKey publicKey(String name, GetPublicKeyResponse response) {
        String spec = response.keySpecAsString();
        try {
            X509EncodedKeySpec der = new X509EncodedKeySpec(response.publicKey().asByteArray());
            return KeyFactory.getInstance(spec != null && spec.startsWith("ECC_") ? "EC" : "RSA").generatePublic(der);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new UnavailableException("AWS KMS returned an unreadable public key for key " + name, e, provider,
                    null);
        }
    }

    // MACs

    @Override
    public byte[] generateMac(String nativeName, byte[] data) {
        return kms.generateMac(b -> b.keyId(keyIdOf(nativeName)).message(SdkBytes.fromByteArray(data))
                .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)).mac().asByteArray();
    }

    @Override
    public CompletableFuture<byte[]> generateMacAsync(String nativeName, byte[] data, Executor blocking) {
        return kmsAsync.get().generateMac(b -> b.keyId(keyIdOf(nativeName)).message(SdkBytes.fromByteArray(data))
                        .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256))
                .thenApply(response -> response.mac().asByteArray());
    }

    /** KMS answers a mismatch with {@code KMSInvalidMacException}, not {@code MacValid=false}: both are false here. */
    @Override
    public boolean verifyMac(String nativeName, byte[] data, byte[] mac) {
        try {
            return Boolean.TRUE.equals(kms.verifyMac(b -> b.keyId(keyIdOf(nativeName))
                    .message(SdkBytes.fromByteArray(data)).mac(SdkBytes.fromByteArray(mac))
                    .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)).macValid());
        } catch (KmsException e) {
            if (invalidMac(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public CompletableFuture<Boolean> verifyMacAsync(String nativeName, byte[] data, byte[] mac, Executor blocking) {
        return kmsAsync.get().verifyMac(b -> b.keyId(keyIdOf(nativeName)).message(SdkBytes.fromByteArray(data))
                        .mac(SdkBytes.fromByteArray(mac)).macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256))
                .handle((response, error) -> {
                    if (error == null) {
                        return Boolean.TRUE.equals(response.macValid());
                    }
                    Throwable cause = unwrapCompletion(error);
                    if (cause instanceof KmsException e && invalidMac(e)) {
                        return false;
                    }
                    throw error instanceof CompletionException completion ? completion
                            : new CompletionException(error);
                });
    }

    private static boolean invalidMac(KmsException e) {
        return "KMSInvalidMacException".equals(code(e));
    }

    // Location, capabilities, errors

    /**
     * Whether {@code keyId} is the ARN of a KMS key in this store's region. A key in another account of the region is
     * in the store: KMS uses it when that key's policy allows, and refuses it otherwise.
     */
    @Override
    public boolean inLocation(String keyId) {
        if (!keyId.startsWith(SCHEME + ":arn:")) {
            return false;
        }
        String[] arn = keyId.substring(SCHEME.length() + 1).split(":", 6);
        return arn.length == 6 && arn[2].equals("kms") && arn[3].equals(region) && !arn[4].isEmpty()
                && arn[5].startsWith("key/") && arn[5].length() > "key/".length();
    }

    @Override
    public boolean unwraps(WrapAlgorithm algorithm) {
        return algorithm == WrapAlgorithm.AWS_KMS;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    /** What KMS is given for {@code nativeName}: the alias for a portable name, else the name as configured. */
    String keyIdOf(String nativeName) {
        return nativeNames ? nativeName : ALIAS + nativeName;
    }

    private void noVersion(String name, Optional<String> version) {
        if (version.isPresent()) {
            throw new InvalidArgumentException("AWS KMS keys have no versions to choose from (key " + name
                    + ", version " + version.get() + ")", null, provider, null);
        }
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        Throwable cause = unwrapCompletion(error);
        if (cause instanceof KmsException e) {
            // KMS's error text names the key and the problem, never the plaintext or ciphertext it was given.
            String code = code(e);
            String message = e.getMessage();
            CloudException mapped = switch (code == null ? "" : code) {
                case "NotFoundException" -> new NotFoundException(message, e, provider, code);
                case "DisabledException", "KMSInvalidStateException" ->
                        new AccessDeniedException(message, e, provider, code);
                case "InvalidCiphertextException", "IncorrectKeyException" ->
                        new DataIntegrityException(message, e, provider, code);
                case "InvalidKeyUsageException", "InvalidArnException", "UnsupportedOperationException" ->
                        new InvalidArgumentException(message, e, provider, code);
                case "LimitExceededException" -> new ThrottledException(message, e, provider, code);
                case "KeyUnavailableException", "DependencyTimeoutException", "KMSInternalException" ->
                        new UnavailableException(message, e, provider, code);
                default -> null;
            };
            if (mapped != null) {
                return Optional.of(mapped);
            }
        }
        return AwsErrors.translate(cause);
    }

    private static String code(KmsException e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    private static Throwable unwrapCompletion(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(kms);
    }

    @Override
    public String toString() {
        return "KmsKeyStoreDriver[" + region + "]";
    }

    // KMS's key descriptions in Halyx's terms

    /**
     * Symmetric encryption keys wrap, asymmetric signing keys sign, and HMAC-SHA256 keys make MACs. KMS's RSA
     * encryption keys and other HMAC sizes have no purpose here: the {@code aws-kms} profile wraps with symmetric keys.
     */
    static Set<KeyPurpose> purposes(KeyUsageType usage, String spec, List<MacAlgorithmSpec> macs) {
        if (usage == null || spec == null) {
            return Set.of();
        }
        return switch (usage) {
            case ENCRYPT_DECRYPT -> spec.equals("SYMMETRIC_DEFAULT") ? Set.of(KeyPurpose.ENCRYPTION) : Set.of();
            case SIGN_VERIFY -> spec.startsWith("RSA_") || spec.equals("ECC_NIST_P256") || spec.equals("ECC_NIST_P384")
                    ? Set.of(KeyPurpose.SIGNING) : Set.of();
            case GENERATE_VERIFY_MAC -> macs != null && macs.contains(MacAlgorithmSpec.HMAC_SHA_256)
                    ? Set.of(KeyPurpose.MAC) : Set.of();
            default -> Set.of();
        };
    }

    /** The key's signing algorithms that Halyx has, with each ECDSA hash on its own curve. */
    static Set<SigningAlgorithm> algorithms(String spec, List<SigningAlgorithmSpec> offered) {
        Set<SigningAlgorithm> algorithms = EnumSet.noneOf(SigningAlgorithm.class);
        for (SigningAlgorithmSpec algorithm : offered == null ? List.<SigningAlgorithmSpec>of() : offered) {
            switch (algorithm) {
                case RSASSA_PSS_SHA_256 -> algorithms.add(SigningAlgorithm.RSA_PSS_SHA256);
                case RSASSA_PKCS1_V1_5_SHA_256 -> algorithms.add(SigningAlgorithm.RSA_PKCS1_SHA256);
                case ECDSA_SHA_256 -> {
                    if ("ECC_NIST_P256".equals(spec)) {
                        algorithms.add(SigningAlgorithm.ECDSA_P256_SHA256);
                    }
                }
                case ECDSA_SHA_384 -> {
                    if ("ECC_NIST_P384".equals(spec)) {
                        algorithms.add(SigningAlgorithm.ECDSA_P384_SHA384);
                    }
                }
                default -> {
                    // an algorithm Halyx doesn't sign with
                }
            }
        }
        return algorithms;
    }

    /** The key spec in Halyx's names ({@code AES_256}, {@code RSA_2048}, {@code EC_P256}), else KMS's own. */
    static String portableType(String spec) {
        if (spec == null) {
            return "UNKNOWN";
        }
        return switch (spec) {
            case "SYMMETRIC_DEFAULT" -> "AES_256";
            case "ECC_NIST_P256" -> "EC_P256";
            case "ECC_NIST_P384" -> "EC_P384";
            case "ECC_NIST_P521" -> "EC_P521";
            default -> spec; // RSA_2048, HMAC_256 and the rest are already in Halyx's form
        };
    }
}
