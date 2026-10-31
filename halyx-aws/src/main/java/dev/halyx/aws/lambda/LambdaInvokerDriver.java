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

import dev.halyx.aws.AwsErrors;
import dev.halyx.aws.AwsProperties;
import dev.halyx.aws.AwsProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnavailableException;
import dev.halyx.functions.FunctionCapability;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.functions.spi.AbstractFunctionInvokerDriver;
import dev.halyx.json.Json;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Invokes a Lambda function, by name or ARN, at the version or alias {@code aws.lambda.qualifier}. Headers travel as
 * the {@code custom} map of the invocation's {@code ClientContext}, which the function reads from
 * {@code context.getClientContext().getCustom()}. A function error comes back as a 200 response with
 * {@code X-Amz-Function-Error}; it is raised as the {@code UnavailableException} a failed HTTP function gives, with
 * the error payload's {@code errorType} and {@code errorMessage}.
 */
public final class LambdaInvokerDriver extends AbstractFunctionInvokerDriver {

    /** Lambda's synchronous request limit, 6 MB. */
    static final long MAX_PAYLOAD_BYTES = 6L * 1024 * 1024;
    /** Lambda's asynchronous request limit, 1 MB. */
    static final long MAX_TRIGGER_PAYLOAD_BYTES = 1024L * 1024;
    /** Lambda's limit on the base64-encoded {@code ClientContext}. */
    static final long MAX_CLIENT_CONTEXT_BYTES = 3_583;

    /** Fire-and-forget invocations; a {@code ClientContext} is taken only by synchronous ones, so no trigger headers. */
    private static final Capabilities CAPABILITIES = Capabilities.of(FunctionCapability.TRIGGER);

    private final LambdaClient client;
    private final Supplier<LambdaAsyncClient> asyncClient;
    private final FunctionConfig config;

    /** @param asyncClient the non-blocking client for asynchronous invocations, made when first asked for */
    public LambdaInvokerDriver(LambdaClient client, Supplier<LambdaAsyncClient> asyncClient, FunctionConfig config) {
        super(AwsProvider.ID);
        this.client = client;
        this.asyncClient = asyncClient;
        this.config = config;
    }

    @Override
    public String function() {
        return config.name();
    }

    @Override
    public FunctionResponse invoke(FunctionRequest request) {
        return response(client.invoke(request(request, InvocationType.REQUEST_RESPONSE)));
    }

    @Override
    public void trigger(FunctionRequest request) {
        client.invoke(request(request, InvocationType.EVENT));
    }

    @Override
    public CompletableFuture<FunctionResponse> invokeAsync(FunctionRequest request, Executor blocking) {
        return asyncClient.get().invoke(request(request, InvocationType.REQUEST_RESPONSE)).thenApply(this::response);
    }

    @Override
    public CompletableFuture<Void> triggerAsync(FunctionRequest request, Executor blocking) {
        return asyncClient.get().invoke(request(request, InvocationType.EVENT)).thenApply(response -> null);
    }

    private InvokeRequest request(FunctionRequest request, InvocationType type) {
        InvokeRequest.Builder invoke = InvokeRequest.builder()
                .functionName(config.name())
                .invocationType(type)
                .payload(SdkBytes.fromByteArrayUnsafe(request.payloadUnsafe()))
                // Lambda's own client timeout covers one attempt; retries are decided by the client's strategy.
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(config.timeout()));
        config.property(AwsProperties.LAMBDA_QUALIFIER).ifPresent(invoke::qualifier);
        if (!request.headers().isEmpty()) {
            invoke.clientContext(clientContext(request.headers()));
        }
        return invoke.build();
    }

    private FunctionResponse response(InvokeResponse response) {
        byte[] payload = response.payload() == null ? new byte[0] : response.payload().asByteArrayUnsafe();
        if (response.functionError() != null) {
            throw functionError(response.functionError(), payload);
        }
        FunctionResponse.Builder result = FunctionResponse.builder(payload);
        if (response.executedVersion() != null) {
            result.executedVersion(response.executedVersion());
        }
        return result.build();
    }

    /** The error payload is {@code {"errorMessage": ..., "errorType": ...}}, unless the runtime itself failed. */
    private UnavailableException functionError(String kind, byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        Map<String, Object> error = Json.parseObject(text).orElse(Map.of());
        String type = error.get("errorType") instanceof String t ? t : kind;
        String message = error.get("errorMessage") instanceof String m ? m : start(text);
        return new UnavailableException("Function " + config.name() + " failed: " + message, null, provider, type);
    }

    private static String start(String text) {
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }

    /** The base64-encoded JSON {@code ClientContext}, with the headers as its {@code custom} map. */
    static String clientContext(Map<String, String> headers) {
        String json = Json.write(Map.of("custom", headers));
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public long maxPayloadBytes() {
        return MAX_PAYLOAD_BYTES;
    }

    @Override
    public long maxTriggerPayloadBytes() {
        return MAX_TRIGGER_PAYLOAD_BYTES;
    }

    @Override
    public long maxHeaderBytes() {
        return MAX_CLIENT_CONTEXT_BYTES;
    }

    /** Lambda counts the encoded {@code ClientContext} that carries the headers. */
    @Override
    public long headerBytes(Map<String, String> headers) {
        return headers.isEmpty() ? 0 : clientContext(headers).length();
    }

    /** A call that outlasted the function's timeout is unavailable, as on the other clouds. */
    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        if (error instanceof ApiCallAttemptTimeoutException || error instanceof ApiCallTimeoutException) {
            return Optional.of(new UnavailableException("Function " + config.name() + " did not respond within "
                    + config.timeout(), error, provider, null));
        }
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
