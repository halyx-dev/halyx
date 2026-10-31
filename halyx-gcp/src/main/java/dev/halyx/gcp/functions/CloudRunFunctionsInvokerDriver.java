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

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.util.BackOff;
import com.google.api.client.util.ExponentialBackOff;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.ThrottledException;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.functions.spi.AbstractFunctionInvokerDriver;
import dev.halyx.functions.spi.ConnectFailures;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Invokes an HTTP function on Cloud Run with a POST to its URL, with a Google ID token for its audience. A non-2xx
 * status, whether from the function's code or Cloud Run's front end, is mapped by {@link GcpErrors#fromStatus}; the
 * Functions Framework answers a function that throws with 500. Requests go through the connection's blocking HTTP
 * transport, so asynchronous invocations run on the blocking executor.
 */
public final class CloudRunFunctionsInvokerDriver extends AbstractFunctionInvokerDriver {

    /** Cloud Run's request limit, 32 MiB. */
    static final long MAX_PAYLOAD_BYTES = 32L * 1024 * 1024;

    /** Attempts after the first, as the other clouds' SDKs make. */
    static final int RETRIES = 3;

    private final HttpRequestFactory requests;
    private final GenericUrl url;
    private final FunctionConfig config;
    private final Duration connectTimeout;

    /** @param requests makes requests on the connection's transport, with the function's login */
    public CloudRunFunctionsInvokerDriver(HttpRequestFactory requests, GenericUrl url, FunctionConfig config,
                                          Duration connectTimeout) {
        super(GcpProvider.ID);
        this.requests = requests;
        this.url = url;
        this.config = config;
        this.connectTimeout = connectTimeout;
    }

    @Override
    public String function() {
        return config.name();
    }

    /**
     * Idempotent functions are retried after a 429, a 5xx or an I/O failure. Others only after a 429 or a failure to
     * connect, when the function hasn't run; the transport's handler can't tell a failure to connect from others,
     * so those are retried here.
     */
    @Override
    public FunctionResponse invoke(FunctionRequest request) {
        BackOff backOff = new ExponentialBackOff();
        for (int attempt = 0; ; attempt++) {
            try {
                return send(request);
            } catch (IOException e) {
                if (config.idempotent() || attempt >= RETRIES || !ConnectFailures.isConnectFailure(e)) {
                    throw new UncheckedIOException("Could not invoke function " + config.name() + ": " + e.getMessage(), e);
                }
                pause(backOff, e);
            }
        }
    }

    private FunctionResponse send(FunctionRequest request) throws IOException {
        HttpRequest http = requests.buildPostRequest(url, new ByteArrayContent(request.contentType(), request.payloadUnsafe()))
                .setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()))
                .setReadTimeout(Math.toIntExact(config.timeout().toMillis()))
                .setThrowExceptionOnExecuteError(false)
                .setNumberOfRetries(RETRIES)
                .setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff())
                        .setBackOffRequired(config.idempotent()
                                ? response -> response.getStatusCode() == 429 || response.getStatusCode() / 100 == 5
                                : response -> response.getStatusCode() == 429));
        if (config.idempotent()) {
            http.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(new ExponentialBackOff()));
        }
        request.headers().forEach((name, value) -> http.getHeaders().set(name, value));
        HttpResponse response = http.execute();
        try {
            return response(response, read(response));
        } finally {
            response.disconnect();
        }
    }

    private static byte[] read(HttpResponse response) throws IOException {
        try (InputStream body = response.getContent()) {
            return body == null ? new byte[0] : body.readAllBytes();
        }
    }

    private FunctionResponse response(HttpResponse response, byte[] body) {
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw GcpErrors.fromStatus(status, "Function " + config.name() + " returned " + status + ": " + start(body),
                    null, String.valueOf(status),
                    ThrottledException.parseRetryAfter(response.getHeaders().getFirstHeaderStringValue("Retry-After")));
        }
        FunctionResponse.Builder result = FunctionResponse.builder(body);
        String contentType = response.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            result.contentType(contentType);
        }
        for (String name : response.getHeaders().keySet()) {
            String value = response.getHeaders().getFirstHeaderStringValue(name);
            if (value != null) {
                result.header(name, value);
            }
        }
        return result.build();
    }

    private static String start(byte[] body) {
        String text = new String(body, 0, Math.min(body.length, 512), StandardCharsets.UTF_8);
        return body.length <= 512 ? text : text + "...";
    }

    private void pause(BackOff backOff, IOException failure) {
        try {
            long millis = backOff.nextBackOffMillis();
            if (millis == BackOff.STOP) {
                throw new UncheckedIOException("Could not invoke function " + config.name() + ": " + failure.getMessage(),
                        failure);
            }
            Thread.sleep(millis);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException("Interrupted invoking function " + config.name(), failure);
        }
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
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(requests);
    }
}
