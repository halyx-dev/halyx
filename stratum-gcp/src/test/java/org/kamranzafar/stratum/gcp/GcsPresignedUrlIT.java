package org.kamranzafar.stratum.gcp;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.gcp.storage.GcsBlobStoreDriver;
import org.kamranzafar.stratum.interceptor.InterceptorChain;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobStore;
import org.kamranzafar.stratum.storage.DefaultBlobStore;
import org.kamranzafar.stratum.storage.PresignedUrl;
import org.kamranzafar.stratum.storage.StorageCapability;
import org.kamranzafar.stratum.tck.PresignedRequests;
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
    private static final String BUCKET = "stratum-presign";
    private static final String PROJECT = "floci-local";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT));

    private static Stratum stratum;
    private static BlobStore store;
    private static BlobStore signingStore;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        stratum = Stratum.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(endpoint)
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, PROJECT))
                .build();
        store = stratum.provider("gcp").blobStore(BUCKET);
        store.unwrap(Storage.class).create(BucketInfo.of(BUCKET));

        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var signer = ServiceAccountCredentials.newBuilder()
                .setClientEmail("stratum-tck@" + PROJECT + ".iam.gserviceaccount.com")
                .setPrivateKey(generator.generateKeyPair().getPrivate())
                .setProjectId(PROJECT)
                .build();
        Storage signingClient = StorageOptions.http()
                .setHost(endpoint.toString())
                .setProjectId(PROJECT)
                .setCredentials(signer)
                .build()
                .getService();
        signingStore = new DefaultBlobStore(GcpProvider.ID, BUCKET,
                new GcsBlobStoreDriver(BUCKET, signingClient, true), InterceptorChain.empty());
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
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
}
