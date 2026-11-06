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

import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.AliasListEntry;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse;
import software.amazon.awssdk.services.kms.model.KeyState;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.ListAliasesRequest;
import software.amazon.awssdk.services.kms.model.ListAliasesResponse;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.VerifyMacRequest;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KmsKeyStoreDriverTest {

    private static final String ARN = "arn:aws:kms:eu-west-2:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    @Test
    void theStoreIsTheRegionsKeyArns() {
        KmsKeyStoreDriver driver = driver(kms(Map.of()), false);

        assertThat(driver.inLocation("aws-kms:" + ARN)).isTrue();
        assertThat(driver.inLocation("aws-kms:" + ARN.replace("111122223333", "444455556666")))
                .as("another account of the region: KMS decides").isTrue();
        assertThat(driver.inLocation("aws-kms:" + ARN.replace("eu-west-2", "us-east-1"))).isFalse();
        assertThat(driver.inLocation("aws-kms:arn:aws:kms:eu-west-2:111122223333:alias/orders-data")).isFalse();
        assertThat(driver.inLocation("aws-kms:arn:aws:kms:eu-west-2:111122223333:key/")).isFalse();
        assertThat(driver.inLocation("aws-kms:arn:aws:s3:eu-west-2:111122223333:key/x")).isFalse();
        assertThat(driver.inLocation("gcp-kms:" + ARN)).isFalse();
        assertThat(driver.inLocation("aws-kms:arn:aws-cn:kms:eu-west-2:111122223333:key/x"))
                .as("any partition").isTrue();
    }

    @Test
    void portableNamesAreAliasesAndNativeNamesAreUsedAsGiven() {
        assertThat(driver(kms(Map.of()), false).keyIdOf("orders-data")).isEqualTo("alias/orders-data");
        assertThat(driver(kms(Map.of()), true).keyIdOf(ARN)).isEqualTo(ARN);
        assertThat(driver(kms(Map.of()), true).keyIdOf("alias/orders-data")).isEqualTo("alias/orders-data");
    }

    @Test
    void listFollowsEveryPageAndSkipsManagedAndUnusedAliases() {
        List<String> markers = new ArrayList<>();
        KmsClient kms = kms(Map.of(
                "listAliases", request -> {
                    String marker = ((ListAliasesRequest) request).marker();
                    markers.add(String.valueOf(marker));
                    return marker == null
                            ? ListAliasesResponse.builder().truncated(true).nextMarker("page-2").aliases(
                                    alias("alias/aws/s3", "k0"), alias("alias/orders-b", "k1"),
                                    alias("alias/other", "k2")).build()
                            : ListAliasesResponse.builder().truncated(false).aliases(
                                    alias("alias/orders-a", "k3"), alias("alias/orders-unused", null)).build();
                },
                "describeKey", request -> symmetric(((DescribeKeyRequest) request).keyId())));

        List<KeyMetadata> listed = driver(kms, false).list("orders-");

        assertThat(markers).containsExactly("null", "page-2");
        assertThat(listed).extracting(KeyMetadata::name).containsExactly("orders-a", "orders-b");
        assertThat(driver(kms, true).list("alias/orders-")).extracting(KeyMetadata::name)
                .containsExactly("alias/orders-a", "alias/orders-b");
    }

    @Test
    void metadataIsInHalyxTerms() {
        KmsClient kms = kms(Map.of("describeKey", request -> symmetric(((DescribeKeyRequest) request).keyId())));

        KeyMetadata metadata = driver(kms, false).metadata("orders-data");

        assertThat(metadata.name()).isEqualTo("orders-data");
        assertThat(metadata.keyId()).isEqualTo("aws-kms:" + ARN);
        assertThat(metadata.nativeId()).isEqualTo(ARN);
        assertThat(metadata.purposes()).containsExactly(KeyPurpose.ENCRYPTION);
        assertThat(metadata.keyType()).isEqualTo("AES_256");
        assertThat(metadata.currentVersion()).isEmpty();
        assertThat(metadata.versions()).isEmpty();
        assertThat(metadata.enabled()).isTrue();
    }

    @Test
    void purposesAndAlgorithmsFollowTheKeySpec() {
        assertThat(KmsKeyStoreDriver.purposes(KeyUsageType.ENCRYPT_DECRYPT, "SYMMETRIC_DEFAULT", null))
                .containsExactly(KeyPurpose.ENCRYPTION);
        assertThat(KmsKeyStoreDriver.purposes(KeyUsageType.ENCRYPT_DECRYPT, "RSA_2048", null))
                .as("RSA encryption isn't the aws-kms profile").isEmpty();
        assertThat(KmsKeyStoreDriver.purposes(KeyUsageType.SIGN_VERIFY, "ECC_NIST_P384", null))
                .containsExactly(KeyPurpose.SIGNING);
        assertThat(KmsKeyStoreDriver.purposes(KeyUsageType.SIGN_VERIFY, "ECC_SECG_P256K1", null)).isEmpty();
        assertThat(KmsKeyStoreDriver.purposes(KeyUsageType.GENERATE_VERIFY_MAC, "HMAC_256",
                List.of(MacAlgorithmSpec.HMAC_SHA_256))).containsExactly(KeyPurpose.MAC);
        assertThat(KmsKeyStoreDriver.purposes(KeyUsageType.GENERATE_VERIFY_MAC, "HMAC_384",
                List.of(MacAlgorithmSpec.HMAC_SHA_384))).isEmpty();

        assertThat(KmsKeyStoreDriver.algorithms("RSA_2048", List.of(SigningAlgorithmSpec.RSASSA_PSS_SHA_256,
                SigningAlgorithmSpec.RSASSA_PSS_SHA_384, SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)))
                .containsExactlyInAnyOrder(SigningAlgorithm.RSA_PSS_SHA256, SigningAlgorithm.RSA_PKCS1_SHA256);
        assertThat(KmsKeyStoreDriver.algorithms("ECC_NIST_P384", List.of(SigningAlgorithmSpec.ECDSA_SHA_384)))
                .containsExactly(SigningAlgorithm.ECDSA_P384_SHA384);
        assertThat(KmsKeyStoreDriver.portableType("ECC_NIST_P256")).isEqualTo("EC_P256");
        assertThat(KmsKeyStoreDriver.portableType("RSA_3072")).isEqualTo("RSA_3072");
        assertThat(KmsKeyStoreDriver.portableType("HMAC_256")).isEqualTo("HMAC_256");
    }

    @Test
    void anInvalidMacIsFalseNotAnError() {
        KmsClient kms = kms(Map.of("verifyMac", request -> {
            throw kmsError("KMSInvalidMacException", 400);
        }));

        assertThat(driver(kms, false).verifyMac("orders-tags", new byte[] {1}, new byte[32])).isFalse();
    }

    @Test
    void keysHaveNoVersionsToChoose() {
        KmsKeyStoreDriver driver = driver(kms(Map.of()), false);

        assertThatThrownBy(() -> driver.wrapKey("orders-data", Optional.of("2"), new byte[32]))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> driver.publicKey("orders-signing", Optional.of("2")))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void kmsErrorsAreTranslated() {
        KmsKeyStoreDriver driver = driver(kms(Map.of()), false);

        assertThat(driver.translate(kmsError("NotFoundException", 400))).isInstanceOf(NotFoundException.class);
        assertThat(driver.translate(kmsError("DisabledException", 400))).isInstanceOf(AccessDeniedException.class)
                .satisfies(e -> assertThat(e.isRetryable()).isFalse());
        assertThat(driver.translate(kmsError("KMSInvalidStateException", 400)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(driver.translate(kmsError("AccessDeniedException", 400))).isInstanceOf(AccessDeniedException.class);
        assertThat(driver.translate(kmsError("InvalidCiphertextException", 400)))
                .isInstanceOf(DataIntegrityException.class);
        assertThat(driver.translate(kmsError("IncorrectKeyException", 400))).isInstanceOf(DataIntegrityException.class);
        assertThat(driver.translate(kmsError("InvalidKeyUsageException", 400)))
                .isInstanceOf(InvalidArgumentException.class);
        assertThat(driver.translate(kmsError("ThrottlingException", 400))).isInstanceOf(ThrottledException.class);
        assertThat(driver.translate(kmsError("LimitExceededException", 400))).isInstanceOf(ThrottledException.class);
        assertThat(driver.translate(kmsError("KeyUnavailableException", 500))).isInstanceOf(UnavailableException.class);
        assertThat(driver.translate(new CompletionException(kmsError("IncorrectKeyException", 400))))
                .as("from the asynchronous client").isInstanceOf(DataIntegrityException.class);
    }

    // Helpers

    private static KmsKeyStoreDriver driver(KmsClient kms, boolean nativeNames) {
        return new KmsKeyStoreDriver(kms, "eu-west-2", nativeNames, () -> {
            throw new AssertionError("no asynchronous calls here");
        });
    }

    /**
     * A client answering each named call with {@code calls}. The SDK's builder-taking overloads are default methods,
     * which a proxy also receives, so they are built into requests here.
     */
    private static KmsClient kms(Map<String, Function<Object, Object>> calls) {
        return (KmsClient) Proxy.newProxyInstance(KmsClient.class.getClassLoader(), new Class<?>[] {KmsClient.class},
                (proxy, method, args) -> {
                    Function<Object, Object> call = calls.get(method.getName());
                    if (call == null) {
                        throw new AssertionError("unexpected call " + method.getName());
                    }
                    Object request = args == null ? null : args[0];
                    if (request instanceof Consumer<?> consumer) {
                        request = build(method.getName(), consumer);
                    }
                    return call.apply(request);
                });
    }

    @SuppressWarnings("unchecked")
    private static Object build(String call, Consumer<?> consumer) {
        return switch (call) {
            case "listAliases" -> {
                var builder = ListAliasesRequest.builder();
                ((Consumer<ListAliasesRequest.Builder>) consumer).accept(builder);
                yield builder.build();
            }
            case "describeKey" -> {
                var builder = DescribeKeyRequest.builder();
                ((Consumer<DescribeKeyRequest.Builder>) consumer).accept(builder);
                yield builder.build();
            }
            case "verifyMac" -> {
                var builder = VerifyMacRequest.builder();
                ((Consumer<VerifyMacRequest.Builder>) consumer).accept(builder);
                yield builder.build();
            }
            default -> throw new AssertionError("no request builder for " + call);
        };
    }

    private static AliasListEntry alias(String name, String target) {
        return AliasListEntry.builder().aliasName(name).targetKeyId(target).build();
    }

    private static DescribeKeyResponse symmetric(String keyId) {
        return DescribeKeyResponse.builder().keyMetadata(m -> m.keyId("1234abcd").arn(ARN)
                .keyUsage(KeyUsageType.ENCRYPT_DECRYPT).keySpec("SYMMETRIC_DEFAULT").keyState(KeyState.ENABLED)
                .enabled(true)).build();
    }

    private static KmsException kmsError(String code, int status) {
        return (KmsException) KmsException.builder().message(code + " from KMS").statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()).build();
    }
}
