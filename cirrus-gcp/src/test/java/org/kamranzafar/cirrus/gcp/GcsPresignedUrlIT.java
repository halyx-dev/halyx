package org.kamranzafar.cirrus.gcp;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.gcp.storage.GcsBlobStoreDriver;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.DefaultBlobStore;
import org.kamranzafar.cirrus.storage.PresignedUpload;
import org.kamranzafar.cirrus.storage.PresignedUploads;
import org.kamranzafar.cirrus.storage.PresignedUrl;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.StorageCapability;
import org.kamranzafar.cirrus.storage.UploadedPart;
import org.kamranzafar.cirrus.tck.PresignedRequests;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Presigned URLs on GCS need credentials that can sign, which the anonymous emulator setup used by
 * the TCK lacks. This test signs with a throwaway service-account key generated locally (signing is
 * offline), then uses the URLs against Floci. Ordinary operations go through an anonymous client.
 */
@Testcontainers(disabledWithoutDocker = true)
class GcsPresignedUrlIT {

    private static final int PORT = 4588;
    private static final String BUCKET = "cirrus-presign";
    private static final String PROJECT = "floci-local";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT));

    private static Cirrus cirrus;
    private static BlobStore store;
    private static BlobStore signingStore;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        cirrus = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(endpoint)
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, PROJECT))
                .build();
        store = cirrus.provider("gcp").blobStore(BUCKET);
        store.unwrap(Storage.class).create(BucketInfo.of(BUCKET));

        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var signer = ServiceAccountCredentials.newBuilder()
                .setClientEmail("cirrus-tck@" + PROJECT + ".iam.gserviceaccount.com")
                .setPrivateKey(generator.generateKeyPair().getPrivate())
                .setProjectId(PROJECT)
                .build();
        Storage signingClient = StorageOptions.http()
                .setHost(endpoint.toString())
                .setProjectId(PROJECT)
                .setCredentials(new OfflineSigner(signer))
                .build()
                .getService();
        signingStore = new DefaultBlobStore(GcpProvider.ID, BUCKET,
                new GcsBlobStoreDriver(BUCKET, signingClient, true), InterceptorChain.empty());
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    private static BlobKey newKey() {
        return BlobKey.of("presign-" + UUID.randomUUID());
    }

    @Test
    void signingCredentialsEnablePresignCapability() {
        assertThat(store.capabilities().supports(StorageCapability.PRESIGNED_URL)).isFalse();
        assertThat(signingStore.capabilities().supports(StorageCapability.PRESIGNED_URL)).isTrue();
    }

    @Test
    void downloadUrlDownloadsBlob() throws Exception {
        BlobKey key = newKey();
        store.put(key, BlobContent.ofString("signed download"));

        PresignedUrl url = signingStore.presignDownload(key, Duration.ofMinutes(5));
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.noBody());

        assertThat(url.uri().getQuery()).contains("X-Goog-Signature");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("signed download");
    }

    @Test
    void uploadUrlUploadsBlob() throws Exception {
        BlobKey key = newKey();

        PresignedUrl url = signingStore.presignUpload(key, Duration.ofMinutes(5));
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.ofString("signed upload"));

        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertThat(new String(store.getBytes(key))).isEqualTo("signed upload");
    }

    @Test
    void uploadUrlWithContentTypeRequiresAndAppliesIt() throws Exception {
        BlobKey key = newKey();

        PresignedUrl url = signingStore.presignUpload(key, Duration.ofMinutes(5), "application/json");
        HttpResponse<String> response = PresignedRequests.send(url, HttpRequest.BodyPublishers.ofString("{}"));

        assertThat(url.headers()).containsEntry("Content-Type", "application/json");
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        assertThat(store.head(key)).get().satisfies(m -> assertThat(m.contentType()).hasValue("application/json"));
    }

    @Test
    void presignedMultipartUploadAssemblesParts() throws Exception {
        assertThat(store.capabilities().supports(StorageCapability.PRESIGNED_MULTIPART)).isFalse();
        BlobKey key = newKey();
        byte[] content = new byte[PresignedUploads.MIN_PART_SIZE + 4321];
        new Random(3).nextBytes(content);
        PresignedUpload upload = signingStore.startPresignedUpload(key,
                PutOptions.builder().contentType("application/x-test").build());

        List<UploadedPart> parts = new ArrayList<>();
        for (int part : new int[] {2, 1}) {
            int from = (part - 1) * PresignedUploads.MIN_PART_SIZE;
            int length = Math.min(PresignedUploads.MIN_PART_SIZE, content.length - from);
            PresignedUrl url = signingStore.presignUploadPart(upload, part, Duration.ofMinutes(5));
            HttpResponse<String> response = PresignedRequests.send(url,
                    HttpRequest.BodyPublishers.ofByteArray(content, from, length));
            assertThat(url.uri().getQuery()).contains("uploadId=").contains("partNumber=" + part);
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
            parts.add(new UploadedPart(part, response.headers().firstValue("ETag").orElse(null)));
        }

        PutResult result = signingStore.completePresignedUpload(upload, parts);

        assertThat(store.head(key)).get().satisfies(m -> {
            assertThat(m.etag()).isEqualTo(result.etag());
            assertThat(m.contentType()).hasValue("application/x-test");
        });
        assertThat(store.getBytes(key)).isEqualTo(content);
    }

    @Test
    void abortedPresignedUploadCreatesNoBlob() {
        BlobKey key = newKey();
        PresignedUpload upload = signingStore.startPresignedUpload(key, PutOptions.defaults());

        signingStore.abortPresignedUpload(upload);
        signingStore.abortPresignedUpload(upload);

        assertThat(store.exists(key)).isFalse();
    }

    /**
     * Signs URLs with the throwaway key, but never asks Google for an access token: the emulator accepts
     * any bearer token, and API calls (starting a multipart upload) would otherwise try a real token exchange.
     */
    private static final class OfflineSigner extends GoogleCredentials implements ServiceAccountSigner {

        private final ServiceAccountCredentials key;

        OfflineSigner(ServiceAccountCredentials key) {
            this.key = key;
        }

        @Override
        public AccessToken refreshAccessToken() {
            return new AccessToken("floci", Date.from(Instant.now().plus(Duration.ofHours(1))));
        }

        @Override
        public String getAccount() {
            return key.getAccount();
        }

        @Override
        public byte[] sign(byte[] toSign) {
            return key.sign(toSign);
        }
    }
}
