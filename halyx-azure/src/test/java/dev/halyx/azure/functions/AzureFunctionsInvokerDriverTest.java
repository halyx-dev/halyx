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
package dev.halyx.azure.functions;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.DefaultFunctionInvoker;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.interceptor.InterceptorChain;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requests and responses as the Functions host takes and gives them, against a stub HTTP client. */
class AzureFunctionsInvokerDriverTest {

    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<Context> contexts = new ArrayList<>();
    private Response next = new Response(200, new HttpHeaders().set(HttpHeaderName.CONTENT_TYPE, "application/json")
            .set(HttpHeaderName.fromString("X-Resized"), "true"), "{\"ok\":true}");

    private final HttpClient http = new HttpClient() {
        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            return send(request, Context.NONE);
        }

        @Override
        public Mono<HttpResponse> send(HttpRequest request, Context context) {
            requests.add(request);
            contexts.add(context);
            return Mono.just(next);
        }
    };

    private FunctionInvoker invoker(FunctionConfig config) throws Exception {
        var pipeline = new HttpPipelineBuilder().httpClient(http).build();
        var driver = new AzureFunctionsInvokerDriver(pipeline,
                URI.create("https://orders.azurewebsites.net/api/" + config.name()).toURL(), config);
        return new DefaultFunctionInvoker(driver, config, InterceptorChain.empty());
    }

    @Test
    void postsThePayloadWithTheHeadersAndTheTimeout() throws Exception {
        FunctionInvoker invoker = invoker(FunctionConfig.builder("resize-image").timeout(Duration.ofSeconds(20))
                .header("X-App", "orders").build());

        FunctionResponse response = invoker.invoke(FunctionRequest.builder("{\"key\":\"a.png\"}").header("X-Tenant", "acme")
                .build());

        HttpRequest sent = requests.getFirst();
        assertThat(sent.getHttpMethod().name()).isEqualTo("POST");
        assertThat(sent.getUrl()).hasToString("https://orders.azurewebsites.net/api/resize-image");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.CONTENT_TYPE)).isEqualTo("application/json");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.fromString("X-App"))).isEqualTo("orders");
        assertThat(sent.getHeaders().getValue(HttpHeaderName.fromString("X-Tenant"))).isEqualTo("acme");
        assertThat(sent.getBodyAsBinaryData().toString()).isEqualTo("{\"key\":\"a.png\"}");
        assertThat(contexts.getFirst().getData("azure-response-timeout")).contains(Duration.ofSeconds(20));
        assertThat(response.payloadAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.headers()).containsEntry("X-Resized", "true");
        assertThat(response.executedVersion()).isEmpty();
    }

    @Test
    void asyncInvokesThroughThePipeline() throws Exception {
        FunctionResponse response = invoker(FunctionConfig.of("resize-image")).async()
                .invoke(FunctionRequest.of("{}")).join();

        assertThat(response.payloadAsString()).isEqualTo("{\"ok\":true}");
        assertThat(requests).hasSize(1);
    }

    @Test
    void statusesMapAsForAnyAzureService() throws Exception {
        FunctionInvoker invoker = invoker(FunctionConfig.of("resize-image"));

        next = new Response(404, new HttpHeaders(), "");
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.providerErrorCode()).contains("404"));

        // The host answers a function that throws with 500.
        next = new Response(500, new HttpHeaders(), "Exception while executing function: disk full");
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOf(UnavailableException.class)
                .hasMessageContaining("resize-image").hasMessageContaining("disk full");

        next = new Response(429, new HttpHeaders().set(HttpHeaderName.RETRY_AFTER, "4"), "");
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOfSatisfying(ThrottledException.class,
                e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(4)));

        next = new Response(302, new HttpHeaders(), "");
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOf(UnknownCloudException.class);
    }

    @Test
    void thereIsNoTrigger() throws Exception {
        FunctionInvoker invoker = invoker(FunctionConfig.of("resize-image"));

        assertThatThrownBy(() -> invoker.trigger(FunctionRequest.of("{}"))).isInstanceOf(UnsupportedFeatureException.class);
        assertThat(requests).isEmpty();
    }

    /** An HTTP response with a status, headers and a body. */
    private static final class Response extends HttpResponse {

        private final int status;
        private final HttpHeaders headers;
        private final byte[] body;

        Response(int status, HttpHeaders headers, String body) {
            super(null);
            this.status = status;
            this.headers = headers;
            this.body = body.getBytes(StandardCharsets.UTF_8);
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
            return Flux.just(ByteBuffer.wrap(body));
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(body);
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(body, charset));
        }
    }
}
