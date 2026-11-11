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
package dev.halyx.azure.blob;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpRequest;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.common.policy.RequestRetryOptions;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.Checksums;
import dev.halyx.storage.PresignRequest;
import dev.halyx.storage.PresignedUpload;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.storage.spi.ServerSideKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Azure driver sends with an encryption scope, which Floci-az accepts without recording: every write names
 * the scope, and SAS tokens for uploads carry it. Requests are recorded and then failed, so nothing is sent.
 */
class AzureEncryptionScopeTest {

    private static final String SCOPE = "orders-scope";
    private static final String HEADER = "x-ms-encryption-scope";

    private final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
    private final AzureBlobStoreDriver plain = driver();
    private final BlobStoreDriver scoped = plain.withServerSideKey(
            new ServerSideKey("https://acme.vault.azure.net/keys/orders", Map.of(AzureBlobStoreDriver.ENCRYPTION_SCOPE, SCOPE)));

    private AzureBlobStoreDriver driver() {
        HttpClient recorder = request -> {
            sent.add(request);
            return Mono.error(new IllegalStateException("recorded"));
        };
        var credential = new StorageSharedKeyCredential("acme", Base64.getEncoder().encodeToString(new byte[32]));
        var builder = new BlobServiceClientBuilder().endpoint("https://acme.blob.core.windows.net")
                .credential(credential).httpClient(recorder)
                .retryOptions(new RequestRetryOptions(null, 1, (Integer) null, null, null, null));
        BlobServiceAsyncClient async = builder.buildAsyncClient();
        return new AzureBlobStoreDriver("orders", builder.buildClient(), async, true);
    }

    private String lastScope() {
        return sent.getLast().getHeaders().getValue(HEADER);
    }

    private void attempt(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException expected) {
            // The recorder fails every request once it has seen it.
        }
    }

    @Test
    void writesNameTheScope() {
        BlobKey key = BlobKey.of("o-1.json");

        attempt(() -> scoped.put(key, (BlobContent.Sized) BlobContent.ofString("{}"), PutOptions.defaults(), Checksums.none()));
        assertThat(lastScope()).as("put").isEqualTo(SCOPE);

        attempt(() -> scoped.putAsync(key, (BlobContent.OfBytes) BlobContent.ofBytes(new byte[] {1}), PutOptions.defaults(),
                Checksums.none(), Runnable::run).join());
        assertThat(lastScope()).as("async put").isEqualTo(SCOPE);

        attempt(() -> scoped.startMultipartUpload(key, PutOptions.defaults()).uploadPart(1, new byte[] {1}, 1));
        assertThat(lastScope()).as("a staged block").isEqualTo(SCOPE);

        attempt(() -> plain.put(key, (BlobContent.Sized) BlobContent.ofString("{}"), PutOptions.defaults(), Checksums.none()));
        assertThat(lastScope()).as("without a key").isNull();
    }

    @Test
    void uploadSasTokensCarryTheScope() {
        BlobKey key = BlobKey.of("o-1.json");

        String upload = scoped.presign(key, PresignRequest.upload(Duration.ofMinutes(5))).uri().getQuery();
        PresignedUpload multipart = scoped.startPresignedUpload(key, PutOptions.defaults());
        String part = scoped.presignUploadPart(multipart, 1, Duration.ofMinutes(5)).uri().getQuery();

        assertThat(upload).contains("ses=" + SCOPE);
        assertThat(part).contains("ses=" + SCOPE);
        assertThat(plain.presign(key, PresignRequest.upload(Duration.ofMinutes(5))).uri().getQuery()).doesNotContain("ses=");
        assertThat(sent).as("SAS tokens are signed locally").isEmpty();
    }

    @Test
    void aServerSideCopyCantCarryTheScope() {
        assertThatThrownBy(() -> scoped.copy(BlobKey.of("a"), BlobKey.of("b")))
                .isInstanceOf(UnsupportedFeatureException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void aKeyWithoutAScopeIsRefused() {
        assertThatThrownBy(() -> plain.withServerSideKey(new ServerSideKey("https://acme.vault.azure.net/keys/orders",
                Map.of()))).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining(AzureBlobStoreDriver.ENCRYPTION_SCOPE);
    }
}
