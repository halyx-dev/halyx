package org.kamranzafar.cirrus.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultBlobStoreTest {

    private Cirrus cirrus;
    private BlobStore store;

    @BeforeEach
    void setUp() {
        cirrus = Cirrus.builder().register("local", ProviderConfig.of("memory")).build();
        store = cirrus.provider("local").blobStore("bucket");
    }

    @AfterEach
    void tearDown() {
        cirrus.close();
    }

    @Test
    void unsupportedCapabilityIsRejectedBeforeReachingDriver() {
        assertThat(store.capabilities().supports(StorageCapability.PRESIGNED_URL)).isFalse();
        assertThatThrownBy(() -> store.presignDownload(BlobKey.of("k"), Duration.ofMinutes(1)))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("PRESIGNED_URL");
    }

    @Test
    void listAllWalksEveryPage() {
        for (int i = 0; i < 2500; i++) {
            store.put(BlobKey.of("p/" + String.format("%05d", i)), BlobContent.ofString("x"));
        }
        assertThat(store.listAll("p/")).hasSize(2500);
    }

    @Test
    void copyPreservesContentTypeAndMetadata() {
        store.put(BlobKey.of("src"), BlobContent.ofString("data"),
                PutOptions.builder().contentType("text/plain").metadata("a", "1").build());
        store.copy(BlobKey.of("src"), BlobKey.of("dst"));

        BlobMetadata copied = store.head(BlobKey.of("dst")).orElseThrow();
        assertThat(copied.contentType()).hasValue("text/plain");
        assertThat(copied.userMetadata()).containsEntry("a", "1");
    }

    @Test
    void presignRequestsAreValidated() {
        assertThatThrownBy(() -> PresignRequest.download(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PresignRequest.upload(Duration.ofDays(8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PresignRequest(PresignRequest.Method.GET, Duration.ofMinutes(1),
                Optional.of("text/plain"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(PresignRequest.upload(Duration.ofMinutes(1), "image/png").contentType()).hasValue("image/png");
    }

    @Test
    void invalidKeysAreRejected() {
        assertThatThrownBy(() -> BlobKey.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobKey.of("/leading")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conflictingPutConditionsAreRejected() {
        assertThatThrownBy(() -> PutOptions.builder().ifNotExists().ifMatch(ETag.of("x")).build())
                .isInstanceOf(IllegalStateException.class);
    }
}
