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

import com.azure.core.http.HttpHeader;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import dev.halyx.azure.AzureErrors;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.functions.spi.AbstractFunctionInvokerDriver;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Invokes an HTTP-triggered Azure function with a POST to its URL, through a pipeline that logs in with an Entra ID
 * token or a function key. A non-2xx status, whether from the function's code or the Functions host, is mapped by
 * {@link AzureErrors#fromStatus}; the host answers a function that throws with 500.
 */
public final class AzureFunctionsInvokerDriver extends AbstractFunctionInvokerDriver {

    /** The Functions host's request limit, 100 MB. */
    static final long MAX_PAYLOAD_BYTES = 100L * 1024 * 1024;

    /** The Netty client's per-request override of its response timeout. */
    private static final String RESPONSE_TIMEOUT = "azure-response-timeout";

    private final HttpPipeline pipeline;
    private final URL url;
    private final FunctionConfig config;

    /** @param pipeline sends with the connection's HTTP client, the function's login and its retry policy */
    public AzureFunctionsInvokerDriver(HttpPipeline pipeline, URL url, FunctionConfig config) {
        super(AzureProvider.ID);
        this.pipeline = pipeline;
        this.url = url;
        this.config = config;
    }

    @Override
    public String function() {
        return config.name();
    }

    @Override
    public FunctionResponse invoke(FunctionRequest request) {
        try (HttpResponse response = pipeline.sendSync(request(request), context())) {
            return response(response, response.getBodyAsBinaryData().toBytes());
        }
    }

    @Override
    public CompletableFuture<FunctionResponse> invokeAsync(FunctionRequest request, Executor blocking) {
        return pipeline.send(request(request), context())
                .flatMap(response -> response.getBodyAsByteArray().defaultIfEmpty(new byte[0])
                        .map(body -> response(response, body))
                        .doFinally(signal -> response.close()))
                .toFuture();
    }

    private HttpRequest request(FunctionRequest request) {
        HttpRequest http = new HttpRequest(HttpMethod.POST, url)
                .setHeader(HttpHeaderName.CONTENT_TYPE, request.contentType())
                .setBody(request.payloadUnsafe());
        request.headers().forEach((name, value) -> http.setHeader(HttpHeaderName.fromString(name), value));
        return http;
    }

    private Context context() {
        return new Context(RESPONSE_TIMEOUT, config.timeout());
    }

    private FunctionResponse response(HttpResponse response, byte[] body) {
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw AzureErrors.fromStatus(status, "Function " + config.name() + " returned " + status + ": " + start(body),
                    null, String.valueOf(status), AzureErrors.retryAfter(response));
        }
        FunctionResponse.Builder result = FunctionResponse.builder(body);
        String contentType = response.getHeaderValue(HttpHeaderName.CONTENT_TYPE);
        if (contentType != null && !contentType.isBlank()) {
            result.contentType(contentType);
        }
        for (HttpHeader header : response.getHeaders()) {
            result.header(header.getName(), header.getValue());
        }
        return result.build();
    }

    private static String start(byte[] body) {
        String text = new String(body, 0, Math.min(body.length, 512), StandardCharsets.UTF_8);
        return body.length <= 512 ? text : text + "...";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.none();
    }

    @Override
    public long maxPayloadBytes() {
        return MAX_PAYLOAD_BYTES;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AzureErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(pipeline);
    }
}
