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
package dev.halyx.gcp.functions;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.DefaultFunctionInvoker;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.interceptor.InterceptorChain;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requests and responses as Cloud Run takes and gives them, against a mock transport. */
class CloudRunFunctionsInvokerDriverTest {

    private static final String URL = "https://europe-west2-orders.cloudfunctions.net/resize-image";

    /** What each attempt gets, in order: a response, or an I/O failure to throw. */
    private final Deque<Object> outcomes = new ArrayDeque<>();
    private final List<Sent> sent = new ArrayList<>();

    private record Sent(String method, String url, String contentType, String body, String tenant, int readTimeout) {
    }

    private final MockHttpTransport transport = new MockHttpTransport() {
        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest(url) {
                private int readTimeout;

                @Override
                public void setTimeout(int connectTimeout, int readTimeout) {
                    this.readTimeout = readTimeout;
                }

                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    sent.add(new Sent(method, url, getContentType(), getContentAsString(), getFirstHeaderValue("X-Tenant"),
                            readTimeout));
                    Object outcome = outcomes.isEmpty() ? ok() : outcomes.pop();
                    if (outcome instanceof IOException failure) {
                        throw failure;
                    }
                    return (LowLevelHttpResponse) outcome;
                }
            };
        }
    };

    private static MockLowLevelHttpResponse ok() {
        return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json")
                .addHeader("X-Resized", "true").setContent("{\"ok\":true}");
    }

    private static MockLowLevelHttpResponse status(int status, String body) {
        return new MockLowLevelHttpResponse().setStatusCode(status).setContent(body);
    }

    private FunctionInvoker invoker(FunctionConfig config) {
        var driver = new CloudRunFunctionsInvokerDriver(transport.createRequestFactory(), new GenericUrl(URL), config,
                Duration.ofSeconds(10));
        return new DefaultFunctionInvoker(driver, config, InterceptorChain.empty());
    }

    @Test
    void postsThePayloadWithTheHeadersAndTheTimeout() {
        FunctionInvoker invoker = invoker(FunctionConfig.builder("resize-image").timeout(Duration.ofSeconds(20)).build());

        FunctionResponse response = invoker.invoke(FunctionRequest.builder("{\"key\":\"a.png\"}").header("X-Tenant", "acme")
                .build());

        assertThat(sent).containsExactly(new Sent("POST", URL, "application/json", "{\"key\":\"a.png\"}", "acme", 20_000));
        assertThat(response.payloadAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.headers()).containsEntry("x-resized", "true");
    }

    @Test
    void statusesMapAsForAnyGoogleCloudService() {
        FunctionInvoker invoker = invoker(FunctionConfig.of("resize-image"));

        outcomes.push(status(404, ""));
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.providerErrorCode()).contains("404"));

        // The Functions Framework answers a function that throws with 500.
        outcomes.push(status(500, "Internal Server Error: disk full"));
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOf(UnavailableException.class)
                .hasMessageContaining("resize-image").hasMessageContaining("disk full");
    }

    @Test
    void nonIdempotentFunctionsAreRetriedOnlyWhenTheyDidNotRun() {
        FunctionInvoker invoker = invoker(FunctionConfig.of("resize-image"));

        outcomes.push(status(500, "failed"));
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOf(UnavailableException.class);
        assertThat(sent).hasSize(1);

        sent.clear();
        outcomes.add(new SocketTimeoutException("Read timed out"));
        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOf(UnavailableException.class);
        assertThat(sent).hasSize(1);

        sent.clear();
        outcomes.add(status(429, ""));
        outcomes.add(new ConnectException("Connection refused"));
        assertThat(invoker.invoke(FunctionRequest.of("{}")).payloadAsString()).isEqualTo("{\"ok\":true}");
        assertThat(sent).hasSize(3);
    }

    @Test
    void idempotentFunctionsAreRetriedAfterServerErrors() {
        FunctionInvoker invoker = invoker(FunctionConfig.builder("resize-image").idempotent(true).build());
        outcomes.add(status(503, "busy"));
        outcomes.add(new SocketTimeoutException("Read timed out"));

        assertThat(invoker.invoke(FunctionRequest.of("{}")).payloadAsString()).isEqualTo("{\"ok\":true}");
        assertThat(sent).hasSize(3);
    }

    @Test
    void throttlingKeepsTheRetryAfterOnceRetriesRunOut() {
        FunctionInvoker invoker = invoker(FunctionConfig.of("resize-image"));
        for (int i = 0; i <= CloudRunFunctionsInvokerDriver.RETRIES; i++) {
            outcomes.add(status(429, "").addHeader("Retry-After", "4"));
        }

        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOfSatisfying(ThrottledException.class,
                e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(4)));
    }

    @Test
    void thereIsNoTrigger() {
        assertThatThrownBy(() -> invoker(FunctionConfig.of("resize-image")).trigger(FunctionRequest.of("{}")))
                .isInstanceOf(UnsupportedFeatureException.class);
        assertThat(sent).isEmpty();
    }
}
