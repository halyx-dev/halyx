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

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.functions.FunctionConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the connection configures an Azure function: its URL, its login and its retries. */
class AzureFunctionsConfigTest {

    private static void withProvider(Credentials credentials, Consumer<CloudContext> test) {
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure").credentials(credentials)).build()) {
            test.accept(halyx.provider("azure"));
        }
    }

    @Test
    void aFunctionNeedsItsAppsUrl() {
        withProvider(Credentials.anonymous(), azure -> assertThatThrownBy(() -> azure.functionInvoker("resize-image"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("azure.functions.endpoint"));
    }

    @Test
    void aFunctionNeedsALoginUnlessTheProviderIsAnonymous() {
        FunctionConfig noLogin = FunctionConfig.builder("resize-image")
                .property("azure.functions.endpoint", "https://orders.azurewebsites.net").build();

        withProvider(Credentials.defaultChain(), azure -> assertThatThrownBy(() -> azure.functionInvoker(noLogin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("azure.functions.audience")
                .hasMessageContaining("azure.functions.key"));
        withProvider(Credentials.anonymous(), azure -> assertThat(azure.functionInvoker(noLogin).function())
                .isEqualTo("resize-image"));
    }

    @Test
    void anAudienceNeedsEntraIdAndExcludesAKey() {
        FunctionConfig audience = FunctionConfig.builder("resize-image")
                .property("azure.functions.endpoint", "https://orders.azurewebsites.net")
                .property("azure.functions.audience", "api://orders-functions").build();
        FunctionConfig both = FunctionConfig.builder("resize-image")
                .property("azure.functions.endpoint", "https://orders.azurewebsites.net")
                .property("azure.functions.audience", "api://orders-functions")
                .property("azure.functions.key", "secret").build();

        withProvider(Credentials.of("account", "key"), azure -> assertThatThrownBy(() -> azure.functionInvoker(audience))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Entra ID"));
        withProvider(Credentials.defaultChain(), azure -> {
            assertThat(azure.functionInvoker(audience).function()).isEqualTo("resize-image");
            assertThatThrownBy(() -> azure.functionInvoker(both)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("set one");
        });
    }

    @Test
    void nonIdempotentFunctionsAreRetriedOnlyWhenTheyDidNotRun() {
        assertThat(attempts(false, () -> response(500))).isEqualTo(1);
        assertThat(attempts(false, () -> response(429))).isEqualTo(4);
        assertThat(attempts(false, () -> {
            throw new RuntimeException(new ConnectException("Connection refused"));
        })).isEqualTo(4);
    }

    @Test
    void idempotentFunctionsUseTheDefaultRetryPolicy() {
        assertThat(attempts(true, () -> response(500))).isEqualTo(4);
    }

    /** How many times the pipeline sends a request whose every attempt gets {@code outcome}. */
    private static int attempts(boolean idempotent, Supplier<HttpResponse> outcome) {
        Deque<Integer> sent = new ArrayDeque<>();
        HttpClient http = new HttpClient() {
            @Override
            public Mono<HttpResponse> send(HttpRequest request) {
                return send(request, Context.NONE);
            }

            @Override
            public Mono<HttpResponse> send(HttpRequest request, Context context) {
                sent.push(1);
                return Mono.fromSupplier(outcome);
            }
        };
        var pipeline = new HttpPipelineBuilder().httpClient(http).policies(AzureConnection.functionRetries(idempotent)).build();
        try {
            pipeline.sendSync(new HttpRequest(HttpMethod.POST, "https://orders.azurewebsites.net/api/f"),
                    Context.NONE).close();
        } catch (RuntimeException expected) {
            // the last attempt's failure
        }
        return sent.size();
    }

    private static HttpResponse response(int status) {
        return new HttpResponse(null) {
            @Override
            public int getStatusCode() {
                return status;
            }

            @Override
            @Deprecated
            public String getHeaderValue(String name) {
                return null;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
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
        };
    }
}
