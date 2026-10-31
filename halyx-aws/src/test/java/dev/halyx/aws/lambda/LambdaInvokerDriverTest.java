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
package dev.halyx.aws.lambda;

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.DefaultFunctionInvoker;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.json.Json;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.ServiceException;
import software.amazon.awssdk.services.lambda.model.TooManyRequestsException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requests and responses as Lambda's Invoke takes and gives them, against a stub client. */
class LambdaInvokerDriverTest {

    private final List<InvokeRequest> requests = new ArrayList<>();
    private InvokeResponse next = InvokeResponse.builder().statusCode(200).payload(SdkBytes.fromUtf8String("{\"ok\":true}"))
            .executedVersion("7").build();

    private final LambdaClient lambda = new LambdaClient() {
        @Override
        public InvokeResponse invoke(InvokeRequest request) {
            requests.add(request);
            return next;
        }

        @Override
        public String serviceName() {
            return "lambda";
        }

        @Override
        public void close() {
        }
    };

    private FunctionInvoker invoker(FunctionConfig config) {
        LambdaInvokerDriver driver = new LambdaInvokerDriver(lambda, () -> {
            throw new UnsupportedOperationException("blocking only");
        }, config);
        return new DefaultFunctionInvoker(driver, config, InterceptorChain.empty());
    }

    @Test
    void invokesSynchronouslyAtTheQualifierWithTheTimeout() {
        FunctionInvoker invoker = invoker(FunctionConfig.builder("resize-image").timeout(Duration.ofSeconds(20))
                .property("aws.lambda.qualifier", "live").build());

        FunctionResponse response = invoker.invoke(FunctionRequest.of("{\"key\":\"a.png\"}"));

        InvokeRequest sent = requests.getFirst();
        assertThat(sent.functionName()).isEqualTo("resize-image");
        assertThat(sent.qualifier()).isEqualTo("live");
        assertThat(sent.invocationType()).isEqualTo(InvocationType.REQUEST_RESPONSE);
        assertThat(sent.payload().asUtf8String()).isEqualTo("{\"key\":\"a.png\"}");
        assertThat(sent.clientContext()).isNull();
        assertThat(sent.overrideConfiguration().orElseThrow().apiCallAttemptTimeout()).contains(Duration.ofSeconds(20));
        assertThat(response.payloadAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.headers()).isEmpty();
        assertThat(response.executedVersion()).contains("7");
    }

    @Test
    void headersTravelInTheClientContext() {
        FunctionInvoker invoker = invoker(FunctionConfig.builder("resize-image").header("X-App", "orders").build());

        invoker.invoke(FunctionRequest.builder("{}").header("X-Tenant", "acme").build());

        String context = new String(Base64.getDecoder().decode(requests.getFirst().clientContext()), StandardCharsets.UTF_8);
        assertThat(Json.at(Json.parse(context), "custom")).isEqualTo(Map.of("X-App", "orders", "X-Tenant", "acme"));
    }

    @Test
    void headersAreLimitedByTheEncodedClientContext() {
        FunctionInvoker invoker = invoker(FunctionConfig.of("resize-image"));
        // 2,700 bytes of value: under 3,583 raw, but over it once wrapped in the ClientContext and base64-encoded.
        FunctionRequest request = FunctionRequest.builder("{}").header("X-Big", "x".repeat(2_700)).build();

        assertThatThrownBy(() -> invoker.invoke(request)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("3583");
        assertThat(requests).isEmpty();
    }

    @Test
    void aFunctionErrorIsUnavailableWithItsTypeAndMessage() {
        next = InvokeResponse.builder().statusCode(200).functionError("Unhandled").payload(SdkBytes.fromUtf8String(
                "{\"errorMessage\":\"disk full\",\"errorType\":\"java.lang.IllegalStateException\",\"stackTrace\":[]}")).build();

        assertThatThrownBy(() -> invoker(FunctionConfig.of("resize-image")).invoke(FunctionRequest.of("{}")))
                .isInstanceOfSatisfying(UnavailableException.class, e -> {
                    assertThat(e.getMessage()).contains("resize-image").contains("disk full");
                    assertThat(e.providerErrorCode()).contains("java.lang.IllegalStateException");
                });
    }

    @Test
    void aRuntimeFailureWithoutAnErrorPayloadKeepsTheErrorKind() {
        next = InvokeResponse.builder().statusCode(200).functionError("Unhandled")
                .payload(SdkBytes.fromUtf8String("Runtime exited without providing a reason")).build();

        assertThatThrownBy(() -> invoker(FunctionConfig.of("resize-image")).invoke(FunctionRequest.of("{}")))
                .isInstanceOfSatisfying(UnavailableException.class, e -> {
                    assertThat(e.getMessage()).contains("Runtime exited without providing a reason");
                    assertThat(e.providerErrorCode()).contains("Unhandled");
                });
    }

    @Test
    void triggerIsAnEventInvocationWithoutHeaders() {
        next = InvokeResponse.builder().statusCode(202).build();
        FunctionInvoker invoker = invoker(FunctionConfig.of("audit"));

        invoker.trigger(FunctionRequest.of("{\"id\":1}"));

        assertThat(requests.getFirst().invocationType()).isEqualTo(InvocationType.EVENT);
        assertThatThrownBy(() -> invoker.trigger(FunctionRequest.builder("{}").header("X-Tenant", "acme").build()))
                .isInstanceOf(UnsupportedFeatureException.class);
        assertThatThrownBy(() -> invoker.trigger(FunctionRequest.of(new byte[1024 * 1024 + 1])))
                .isInstanceOf(InvalidArgumentException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void sdkRetriesOfNonIdempotentFunctionsAreOnlyBeforeTheFunctionRuns() {
        assertThat(LambdaClients.beforeSend(TooManyRequestsException.builder().statusCode(429).message("Rate exceeded").build()))
                .isTrue();
        assertThat(LambdaClients.beforeSend(SdkClientException.create("Unable to execute HTTP request",
                new ConnectException("Connection refused")))).isTrue();
        // The request may have reached the function: a read timeout, or a 5xx from Lambda.
        assertThat(LambdaClients.beforeSend(SdkClientException.create("Unable to execute HTTP request",
                new SocketTimeoutException("Read timed out")))).isFalse();
        assertThat(LambdaClients.beforeSend(ServiceException.builder()
                .statusCode(500).message("internal").build())).isFalse();
    }
}
