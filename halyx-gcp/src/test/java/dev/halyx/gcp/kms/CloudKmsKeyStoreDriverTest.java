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

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.gax.rpc.ApiExceptionFactory;
import com.google.api.gax.rpc.PageContext;
import com.google.api.gax.rpc.PagedListDescriptor;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionState;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.GetCryptoKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceClient.ListCryptoKeyVersionsPagedResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient.ListCryptoKeysPagedResponse;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsResponse;
import com.google.cloud.kms.v1.ListCryptoKeysRequest;
import com.google.cloud.kms.v1.ListCryptoKeysResponse;
import com.google.cloud.kms.v1.MacSignRequest;
import com.google.cloud.kms.v1.MacSignResponse;
import com.google.cloud.kms.v1.MacVerifyRequest;
import com.google.cloud.kms.v1.MacVerifyResponse;
import com.google.cloud.kms.v1.stub.KeyManagementServiceStub;
import com.google.protobuf.ByteString;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The driver against a fake Cloud KMS: the parts Floci-gcp can't exercise, which are MACs, CRC32C failures, error
 * mapping and listing across pages.
 */
class CloudKmsKeyStoreDriverTest {

    private static final String RING = "projects/acme/locations/global/keyRings/orders";
    private static final String TAGS = RING + "/cryptoKeys/tags";

    @Test
    void theStoreIsItsKeyRing() {
        CloudKmsKeyStoreDriver driver = driver(new FakeKms(), false);

        assertThat(driver.inLocation("gcp-kms:" + RING + "/cryptoKeys/data")).isTrue();
        assertThat(driver.inLocation("gcp-kms:" + RING + "/cryptoKeys/data/cryptoKeyVersions/1")).isFalse();
        assertThat(driver.inLocation("gcp-kms:projects/acme/locations/global/keyRings/other/cryptoKeys/data"))
                .isFalse();
        assertThat(driver.unwraps(WrapAlgorithm.GCP_KMS)).isTrue();
        assertThat(driver.unwraps(WrapAlgorithm.AWS_KMS)).isFalse();
        assertThatThrownBy(() -> driver(new FakeKms(), false, "orders")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nativeNamesAlsoTakeTheFullNameInTheKeyRing() {
        CloudKmsKeyStoreDriver nativeNames = driver(new FakeKms(), true);

        assertThat(nativeNames.keyNameOf("data")).isEqualTo(RING + "/cryptoKeys/data");
        assertThat(nativeNames.keyNameOf(RING + "/cryptoKeys/data")).isEqualTo(RING + "/cryptoKeys/data");
        assertThatThrownBy(() -> nativeNames.keyNameOf("projects/acme/locations/global/keyRings/x/cryptoKeys/data"))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> nativeNames.keyNameOf(RING + "/cryptoKeys/data/cryptoKeyVersions/1"))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void keyTypesPurposesAndAlgorithmsFollowTheVersionAlgorithm() {
        assertThat(CloudKmsKeyStoreDriver.keyType(CryptoKeyVersionAlgorithm.RSA_SIGN_PKCS1_2048_SHA256))
                .as("the size, not PKCS1's 1").isEqualTo("RSA_2048");
        assertThat(CloudKmsKeyStoreDriver.keyType(CryptoKeyVersionAlgorithm.RSA_SIGN_PSS_4096_SHA256))
                .isEqualTo("RSA_4096");
        assertThat(CloudKmsKeyStoreDriver.keyType(CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION))
                .isEqualTo("AES_256");
        assertThat(CloudKmsKeyStoreDriver.keyType(CryptoKeyVersionAlgorithm.EC_SIGN_P384_SHA384)).isEqualTo("EC_P384");
        assertThat(CloudKmsKeyStoreDriver.keyType(CryptoKeyVersionAlgorithm.HMAC_SHA256)).isEqualTo("HMAC_256");
        assertThat(CloudKmsKeyStoreDriver.signing(CryptoKeyVersionAlgorithm.RSA_SIGN_PSS_3072_SHA256))
                .contains(SigningAlgorithm.RSA_PSS_SHA256);
        assertThat(CloudKmsKeyStoreDriver.signing(CryptoKeyVersionAlgorithm.RSA_SIGN_PKCS1_4096_SHA512)).isEmpty();
        assertThat(CloudKmsKeyStoreDriver.purposes(CryptoKeyPurpose.MAC, CryptoKeyVersionAlgorithm.HMAC_SHA256))
                .containsExactly(KeyPurpose.MAC);
        assertThat(CloudKmsKeyStoreDriver.purposes(CryptoKeyPurpose.MAC, CryptoKeyVersionAlgorithm.HMAC_SHA512))
                .isEmpty();
        assertThat(CloudKmsKeyStoreDriver.purposes(CryptoKeyPurpose.ASYMMETRIC_DECRYPT,
                CryptoKeyVersionAlgorithm.RSA_DECRYPT_OAEP_2048_SHA256)).isEmpty();
    }

    @Test
    void aKeyWithoutAPrimaryUsesItsNewestEnabledVersion() {
        FakeKms kms = new FakeKms().macKey(TAGS, version(TAGS, 1, true), version(TAGS, 2, true),
                version(TAGS, 10, true), version(TAGS, 11, false));

        KeyMetadata metadata = driver(kms, false).metadata("tags");

        assertThat(metadata.currentVersion()).as("10, not 2 or 9: versions are numbers").contains("10");
        assertThat(metadata.versions()).containsExactly("1", "2", "10");
        assertThat(metadata.purposes()).containsExactly(KeyPurpose.MAC);
        assertThat(metadata.enabled()).isTrue();
    }

    @Test
    void macsAreMadeWithTheNewestVersionAndVerifiedWithAnyEnabledOne() {
        FakeKms kms = new FakeKms().macKey(TAGS, version(TAGS, 1, true), version(TAGS, 2, true));
        CloudKmsKeyStoreDriver driver = driver(kms, false);
        byte[] data = "orders/o-1".getBytes();

        byte[] mac = driver.generateMac("tags", data);

        assertThat(kms.macSignedWith).isEqualTo(TAGS + "/cryptoKeyVersions/2");
        assertThat(driver.verifyMac("tags", data, mac)).isTrue();
        assertThat(driver.verifyMac("tags", data, FakeKms.mac(TAGS + "/cryptoKeyVersions/1", data)))
                .as("made before a rotation").isTrue();
        assertThat(driver.verifyMac("tags", "other".getBytes(), mac)).isFalse();
        assertThat(driver.verifyMacAsync("tags", data, mac, Runnable::run).join()).isTrue();
        assertThat(driver.generateMacAsync("tags", data, Runnable::run).join()).isEqualTo(mac);
    }

    @Test
    void aWrongResponseChecksumIsAnIntegrityError() {
        FakeKms kms = new FakeKms();
        kms.encrypt = request -> EncryptResponse.newBuilder().setName(request.getName() + "/cryptoKeyVersions/1")
                .setCiphertext(ByteString.copyFromUtf8("wrapped")).setVerifiedPlaintextCrc32C(true)
                .setCiphertextCrc32C(com.google.protobuf.Int64Value.of(1)).build();

        assertThatThrownBy(() -> driver(kms, false).wrapKey("data", Optional.empty(), new byte[32]))
                .isInstanceOf(DataIntegrityException.class).hasMessageContaining("CRC32C");
    }

    @Test
    void anUnverifiedRequestChecksumIsAnIntegrityError() {
        FakeKms kms = new FakeKms();
        kms.encrypt = request -> EncryptResponse.newBuilder().setName(request.getName() + "/cryptoKeyVersions/1")
                .setCiphertext(ByteString.copyFromUtf8("wrapped"))
                .setCiphertextCrc32C(CloudKmsKeyStoreDriver.crc(ByteString.copyFromUtf8("wrapped"))).build();

        assertThatThrownBy(() -> driver(kms, false).wrapKey("data", Optional.empty(), new byte[32]))
                .isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void wrapsRecordThePrimaryVersionTheServiceUsed() {
        FakeKms kms = new FakeKms();
        kms.encrypt = request -> {
            ByteString ciphertext = ByteString.copyFromUtf8("wrapped");
            return EncryptResponse.newBuilder().setName(request.getName() + "/cryptoKeyVersions/7")
                    .setCiphertext(ciphertext).setCiphertextCrc32C(CloudKmsKeyStoreDriver.crc(ciphertext))
                    .setVerifiedPlaintextCrc32C(true).build();
        };

        WrappedKey wrapped = driver(kms, false).wrapKey("data", Optional.empty(), new byte[32]);

        assertThat(wrapped.keyId()).isEqualTo("gcp-kms:" + RING + "/cryptoKeys/data");
        assertThat(wrapped.version()).contains("7");
        assertThat(kms.encryptedWith).as("the crypto key, so its primary encrypts").isEqualTo(RING + "/cryptoKeys/data");
    }

    @Test
    void errorsAreTranslated() {
        FakeKms kms = new FakeKms();
        kms.decrypt = request -> {
            throw error(Status.Code.INVALID_ARGUMENT, "Decryption failed: the ciphertext is invalid.");
        };
        CloudKmsKeyStoreDriver driver = driver(kms, false);
        WrappedKey wrapped = new WrappedKey("gcp-kms:" + RING + "/cryptoKeys/data", Optional.of("1"),
                WrapAlgorithm.GCP_KMS, new byte[64]);

        assertThatThrownBy(() -> driver.unwrapKey(wrapped)).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> driver.unwrapKeyAsync(wrapped, Runnable::run).join())
                .hasCauseInstanceOf(DataIntegrityException.class);
        assertThat(driver.translate(error(Status.Code.FAILED_PRECONDITION, "The key version is not enabled")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(driver.translate(error(Status.Code.INVALID_ARGUMENT, "Checksum verification failed")))
                .isInstanceOf(DataIntegrityException.class);
        assertThat(driver.translate(error(Status.Code.INVALID_ARGUMENT, "Bad digest length")))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void listingFollowsEveryPage() {
        FakeKms kms = new FakeKms();
        for (String id : List.of("orders-a", "orders-b", "other", "orders-c")) {
            kms.symmetricKey(RING + "/cryptoKeys/" + id);
        }
        kms.pageSize = 1;

        assertThat(driver(kms, false).list("orders-")).extracting(KeyMetadata::name)
                .containsExactly("orders-a", "orders-b", "orders-c");
        assertThat(kms.keyPagesServed).isEqualTo(4);
        assertThat(driver(kms, true).list(RING + "/cryptoKeys/orders-")).extracting(KeyMetadata::name)
                .containsExactly(RING + "/cryptoKeys/orders-a", RING + "/cryptoKeys/orders-b",
                        RING + "/cryptoKeys/orders-c");
    }

    // Helpers

    private static CloudKmsKeyStoreDriver driver(FakeKms kms, boolean nativeNames) {
        return driver(kms, nativeNames, RING);
    }

    private static CloudKmsKeyStoreDriver driver(FakeKms kms, boolean nativeNames, String ring) {
        return new CloudKmsKeyStoreDriver(KeyManagementServiceClient.create(kms), ring, nativeNames);
    }

    private static CryptoKeyVersion version(String key, int number, boolean enabled) {
        return CryptoKeyVersion.newBuilder().setName(key + "/cryptoKeyVersions/" + number)
                .setAlgorithm(CryptoKeyVersionAlgorithm.HMAC_SHA256)
                .setState(enabled ? CryptoKeyVersionState.ENABLED : CryptoKeyVersionState.DISABLED).build();
    }

    private static RuntimeException error(Status.Code code, String message) {
        return ApiExceptionFactory.createException(new RuntimeException(message), GrpcStatusCode.of(code), false);
    }

    /** A Cloud KMS that answers only what these tests ask of it, through the client's stub. */
    private static final class FakeKms extends KeyManagementServiceStub {

        final Map<String, CryptoKey> keys = new ConcurrentHashMap<>();
        final Map<String, List<CryptoKeyVersion>> versions = new ConcurrentHashMap<>();
        Function<EncryptRequest, EncryptResponse> encrypt;
        Function<DecryptRequest, DecryptResponse> decrypt;
        int pageSize = 100;
        int keyPagesServed;
        String encryptedWith;
        String macSignedWith;

        FakeKms macKey(String name, CryptoKeyVersion... all) {
            keys.put(name, CryptoKey.newBuilder().setName(name).setPurpose(CryptoKeyPurpose.MAC)
                    .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                            .setAlgorithm(CryptoKeyVersionAlgorithm.HMAC_SHA256)).build());
            versions.put(name, List.of(all));
            return this;
        }

        void symmetricKey(String name) {
            CryptoKeyVersion primary = CryptoKeyVersion.newBuilder().setName(name + "/cryptoKeyVersions/1")
                    .setAlgorithm(CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION)
                    .setState(CryptoKeyVersionState.ENABLED).build();
            keys.put(name, CryptoKey.newBuilder().setName(name).setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT)
                    .setPrimary(primary).build());
            versions.put(name, List.of(primary));
        }

        /** A stand-in HMAC: what matters here is that each version's differs, not the cryptography. */
        static byte[] mac(String version, byte[] data) {
            return CloudKmsKeyStoreDriver.crc(ByteString.copyFromUtf8(version + ":" + new String(data)))
                    .toByteString().toByteArray();
        }

        @Override
        public UnaryCallable<GetCryptoKeyRequest, CryptoKey> getCryptoKeyCallable() {
            return unary(request -> {
                CryptoKey key = keys.get(request.getName());
                if (key == null) {
                    throw error(Status.Code.NOT_FOUND, request.getName() + " not found");
                }
                return key;
            });
        }

        @Override
        public UnaryCallable<ListCryptoKeyVersionsRequest, ListCryptoKeyVersionsPagedResponse>
                listCryptoKeyVersionsPagedCallable() {
            UnaryCallable<ListCryptoKeyVersionsRequest, ListCryptoKeyVersionsResponse> raw = unary(request ->
                    ListCryptoKeyVersionsResponse.newBuilder()
                            .addAllCryptoKeyVersions(versions.getOrDefault(request.getParent(), List.of())).build());
            return new UnaryCallable<>() {
                @Override
                public ApiFuture<ListCryptoKeyVersionsPagedResponse> futureCall(ListCryptoKeyVersionsRequest request,
                                                                                ApiCallContext context) {
                    ApiCallContext ctx = context == null ? GrpcCallContext.createDefault() : context;
                    return ListCryptoKeyVersionsPagedResponse.createAsync(
                            PageContext.create(raw, new VersionPages(), request, ctx), raw.futureCall(request, ctx));
                }
            };
        }

        @Override
        public UnaryCallable<ListCryptoKeysRequest, ListCryptoKeysPagedResponse> listCryptoKeysPagedCallable() {
            UnaryCallable<ListCryptoKeysRequest, ListCryptoKeysResponse> raw = unary(request -> {
                keyPagesServed++;
                List<CryptoKey> all = new ArrayList<>(keys.values());
                all.sort(java.util.Comparator.comparing(CryptoKey::getName));
                int from = request.getPageToken().isEmpty() ? 0 : Integer.parseInt(request.getPageToken());
                int to = Math.min(all.size(), from + pageSize);
                return ListCryptoKeysResponse.newBuilder().addAllCryptoKeys(all.subList(from, to))
                        .setNextPageToken(to < all.size() ? Integer.toString(to) : "").build();
            });
            return new UnaryCallable<>() {
                @Override
                public ApiFuture<ListCryptoKeysPagedResponse> futureCall(ListCryptoKeysRequest request,
                                                                         ApiCallContext context) {
                    ApiCallContext ctx = context == null ? GrpcCallContext.createDefault() : context;
                    return ListCryptoKeysPagedResponse.createAsync(
                            PageContext.create(raw, new KeyPages(), request, ctx), raw.futureCall(request, ctx));
                }
            };
        }

        @Override
        public UnaryCallable<EncryptRequest, EncryptResponse> encryptCallable() {
            return unary(request -> {
                encryptedWith = request.getName();
                return encrypt.apply(request);
            });
        }

        @Override
        public UnaryCallable<DecryptRequest, DecryptResponse> decryptCallable() {
            return unary(request -> decrypt.apply(request));
        }

        @Override
        public UnaryCallable<MacSignRequest, MacSignResponse> macSignCallable() {
            return unary(request -> {
                macSignedWith = request.getName();
                ByteString mac = ByteString.copyFrom(mac(request.getName(), request.getData().toByteArray()));
                return MacSignResponse.newBuilder().setName(request.getName()).setMac(mac)
                        .setMacCrc32C(CloudKmsKeyStoreDriver.crc(mac)).setVerifiedDataCrc32C(true).build();
            });
        }

        @Override
        public UnaryCallable<MacVerifyRequest, MacVerifyResponse> macVerifyCallable() {
            return unary(request -> {
                boolean success = ByteString.copyFrom(mac(request.getName(), request.getData().toByteArray()))
                        .equals(request.getMac());
                return MacVerifyResponse.newBuilder().setName(request.getName()).setSuccess(success)
                        .setVerifiedSuccessIntegrity(success).setVerifiedDataCrc32C(true).setVerifiedMacCrc32C(true)
                        .build();
            });
        }

        private static <Q, R> UnaryCallable<Q, R> unary(Function<Q, R> call) {
            return new UnaryCallable<>() {
                @Override
                public ApiFuture<R> futureCall(Q request, ApiCallContext context) {
                    try {
                        return ApiFutures.immediateFuture(call.apply(request));
                    } catch (RuntimeException e) {
                        return ApiFutures.immediateFailedFuture(e);
                    }
                }
            };
        }

        @Override
        public void close() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public void shutdownNow() {
        }

        @Override
        public boolean awaitTermination(long duration, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    }

    private static final class KeyPages
            implements PagedListDescriptor<ListCryptoKeysRequest, ListCryptoKeysResponse, CryptoKey> {
        @Override
        public String emptyToken() {
            return "";
        }

        @Override
        public ListCryptoKeysRequest injectToken(ListCryptoKeysRequest payload, String token) {
            return payload.toBuilder().setPageToken(token).build();
        }

        @Override
        public ListCryptoKeysRequest injectPageSize(ListCryptoKeysRequest payload, int pageSize) {
            return payload.toBuilder().setPageSize(pageSize).build();
        }

        @Override
        public Integer extractPageSize(ListCryptoKeysRequest payload) {
            return payload.getPageSize();
        }

        @Override
        public String extractNextToken(ListCryptoKeysResponse payload) {
            return payload.getNextPageToken();
        }

        @Override
        public Iterable<CryptoKey> extractResources(ListCryptoKeysResponse payload) {
            return payload.getCryptoKeysList();
        }
    }

    private static final class VersionPages implements
            PagedListDescriptor<ListCryptoKeyVersionsRequest, ListCryptoKeyVersionsResponse, CryptoKeyVersion> {
        @Override
        public String emptyToken() {
            return "";
        }

        @Override
        public ListCryptoKeyVersionsRequest injectToken(ListCryptoKeyVersionsRequest payload, String token) {
            return payload.toBuilder().setPageToken(token).build();
        }

        @Override
        public ListCryptoKeyVersionsRequest injectPageSize(ListCryptoKeyVersionsRequest payload, int pageSize) {
            return payload.toBuilder().setPageSize(pageSize).build();
        }

        @Override
        public Integer extractPageSize(ListCryptoKeyVersionsRequest payload) {
            return payload.getPageSize();
        }

        @Override
        public String extractNextToken(ListCryptoKeyVersionsResponse payload) {
            return payload.getNextPageToken();
        }

        @Override
        public Iterable<CryptoKeyVersion> extractResources(ListCryptoKeyVersionsResponse payload) {
            return payload.getCryptoKeyVersionsList();
        }
    }
}
