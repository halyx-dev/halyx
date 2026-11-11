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
package dev.halyx.gcp.storage;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.NoCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.Checksums;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.StorageTier;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.storage.spi.ServerSideKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the GCS driver sends with a customer-managed key, which Floci-gcp accepts without recording: uploads, copies and
 * tier rewrites name the key. Requests are recorded and answered with an error, except reads of the object.
 */
class GcsCustomerManagedKeyTest {

    private static final String KEY = "projects/p/locations/global/keyRings/r/cryptoKeys/orders";
    private static final String OLD_KEY = "projects/p/locations/global/keyRings/r/cryptoKeys/old";

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final GcsBlobStoreDriver plain = new GcsBlobStoreDriver("b", storage(), false);
    private final BlobStoreDriver keyed = plain.withServerSideKey(new ServerSideKey(KEY, Map.of()));

    private Storage storage() {
        MockHttpTransport transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                sent.add(method + " " + url);
                return new MockLowLevelHttpRequest(url) {
                    @Override
                    public LowLevelHttpResponse execute() {
                        var response = new MockLowLevelHttpResponse().setContentType("application/json");
                        if (method.equals("GET")) {
                            // An object encrypted with an older key, as GCS reports it: with its version.
                            return response.setContent("{\"bucket\":\"b\",\"name\":\"o\",\"generation\":\"7\","
                                    + "\"kmsKeyName\":\"" + OLD_KEY + "/cryptoKeyVersions/3\"}");
                        }
                        return response.setStatusCode(400).setContent("{\"error\":{\"code\":400,\"message\":\"recorded\"}}");
                    }
                };
            }
        };
        return StorageOptions.http().setProjectId("p").setCredentials(NoCredentials.getInstance())
                .setTransportOptions(HttpTransportOptions.newBuilder().setHttpTransportFactory(() -> transport).build())
                .setRetrySettings(RetrySettings.newBuilder().setMaxAttempts(1).build())
                .build().getService();
    }

    /** A query parameter as the SDK sends it, with the slashes of a resource name unescaped. */
    private static String param(String name, String value) {
        return name + "=" + value;
    }

    private void attempt(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException expected) {
            // The transport fails writes once it has seen them.
        }
    }

    @Test
    void uploadsNameTheKey() {
        attempt(() -> keyed.put(BlobKey.of("o"), (BlobContent.Sized) BlobContent.ofString("{}"), PutOptions.defaults(),
                Checksums.none()));
        assertThat(sent.getLast()).contains(param("kmsKeyName", KEY));

        attempt(() -> plain.put(BlobKey.of("o"), (BlobContent.Sized) BlobContent.ofString("{}"), PutOptions.defaults(),
                Checksums.none()));
        assertThat(sent.getLast()).doesNotContain("kmsKeyName");
    }

    @Test
    void copiesNameTheKey() {
        attempt(() -> keyed.copy(BlobKey.of("a"), BlobKey.of("b")));

        assertThat(sent.getLast()).contains("rewriteTo").contains(param("destinationKmsKeyName", KEY));
    }

    @Test
    void tierRewritesKeepTheObjectsKeyOrUseTheDrivers() {
        attempt(() -> plain.setTier(BlobKey.of("o"), StorageTier.COLD));
        assertThat(sent.getLast()).as("the object's own key, without its version")
                .contains(param("destinationKmsKeyName", OLD_KEY)).doesNotContain("cryptoKeyVersions");

        attempt(() -> keyed.setTier(BlobKey.of("o"), StorageTier.COLD));
        assertThat(sent.getLast()).contains(param("destinationKmsKeyName", KEY));
    }
}
