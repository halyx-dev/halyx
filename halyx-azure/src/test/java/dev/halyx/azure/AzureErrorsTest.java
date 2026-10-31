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
package dev.halyx.azure;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.AlreadyExistsException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.error.UnsupportedFeatureException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AzureErrorsTest {

    private static HttpResponseException error(int status) {
        return error(status, new HttpHeaders());
    }

    private static HttpResponseException error(int status, HttpHeaders headers) {
        return new HttpResponseException("boom", new Response(status, headers));
    }

    @Test
    void sdkErrorsMapByStatus() {
        assertThat(AzureErrors.translate(error(400))).get().isInstanceOf(InvalidArgumentException.class);
        assertThat(AzureErrors.translate(error(416))).get().isInstanceOf(InvalidArgumentException.class);
        assertThat(AzureErrors.translate(error(401))).get().isInstanceOf(AccessDeniedException.class);
        assertThat(AzureErrors.translate(error(403))).get().isInstanceOf(AccessDeniedException.class);
        assertThat(AzureErrors.translate(error(404))).get().isInstanceOf(NotFoundException.class);
        assertThat(AzureErrors.translate(error(409))).get().isInstanceOf(AlreadyExistsException.class);
        assertThat(AzureErrors.translate(error(412))).get().isInstanceOf(PreconditionFailedException.class);
        assertThat(AzureErrors.translate(error(501))).get().isInstanceOf(UnsupportedFeatureException.class);
        assertThat(AzureErrors.translate(error(500))).get().isInstanceOf(UnavailableException.class);
        assertThat(AzureErrors.translate(error(504))).get().isInstanceOf(UnavailableException.class);
        // A status with no portable meaning is left to the driver's fallback.
        assertThat(AzureErrors.translate(error(418))).isEmpty();
    }

    @Test
    void throttlingIs429Or503WithTheRetryAfter() {
        // Azure Storage signals throttling with 503 ServerBusy as well as 429.
        HttpHeaders millis = new HttpHeaders().set(HttpHeaderName.fromString("x-ms-retry-after-ms"), "1500")
                .set(HttpHeaderName.RETRY_AFTER, "9");
        HttpHeaders seconds = new HttpHeaders().set(HttpHeaderName.RETRY_AFTER, "7");

        assertThat(((ThrottledException) AzureErrors.translate(error(429, millis)).orElseThrow()).retryAfter())
                .contains(Duration.ofMillis(1500));
        assertThat(((ThrottledException) AzureErrors.translate(error(503, seconds)).orElseThrow()).retryAfter())
                .contains(Duration.ofSeconds(7));
    }

    @Test
    void loginAndNetworkFailures() {
        assertThat(AzureErrors.translate(new ClientAuthenticationException("no identity", null))).get()
                .isInstanceOf(AccessDeniedException.class);
        assertThat(AzureErrors.translate(new UncheckedIOException(new IOException("reset")))).get()
                .isInstanceOf(UnavailableException.class);
        assertThat(AzureErrors.translate(new IllegalStateException("other"))).isEmpty();
    }

    @Test
    void fromStatusMapsAsTheSdkTranslationDoes() {
        assertThat(AzureErrors.fromStatus(400, "boom", null, "400", null)).isInstanceOf(InvalidArgumentException.class);
        assertThat(AzureErrors.fromStatus(416, "boom", null, "416", null)).isInstanceOf(InvalidArgumentException.class);
        assertThat(AzureErrors.fromStatus(401, "boom", null, "401", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(AzureErrors.fromStatus(403, "boom", null, "403", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(AzureErrors.fromStatus(404, "boom", null, "404", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("boom").satisfies(e -> assertThat(e.providerErrorCode()).contains("404"));
        assertThat(AzureErrors.fromStatus(409, "boom", null, "409", null)).isInstanceOf(AlreadyExistsException.class);
        assertThat(AzureErrors.fromStatus(412, "boom", null, "412", null)).isInstanceOf(PreconditionFailedException.class);
        assertThat(AzureErrors.fromStatus(501, "boom", null, "501", null)).isInstanceOf(UnsupportedFeatureException.class);
        assertThat(AzureErrors.fromStatus(500, "boom", null, "500", null)).isInstanceOf(UnavailableException.class);
        assertThat(((ThrottledException) AzureErrors.fromStatus(429, "boom", null, "429", Duration.ofSeconds(3)))
                .retryAfter()).contains(Duration.ofSeconds(3));
        assertThat(AzureErrors.fromStatus(503, "boom", null, "503", null)).isInstanceOf(ThrottledException.class);
        // Where the SDK translation leaves the error to the driver, fromStatus has to answer.
        assertThat(AzureErrors.fromStatus(418, "boom", null, "418", null)).isInstanceOf(UnknownCloudException.class);
    }

    /** An HTTP response with only a status and headers. */
    private static final class Response extends HttpResponse {

        private final int status;
        private final HttpHeaders headers;

        Response(int status, HttpHeaders headers) {
            super(new HttpRequest(HttpMethod.GET, "https://example.invalid/"));
            this.status = status;
            this.headers = headers;
        }

        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        @Deprecated
        public String getHeaderValue(String name) {
            return headers.getValue(HttpHeaderName.fromString(name));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.empty();
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.empty();
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.empty();
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.empty();
        }
    }
}
