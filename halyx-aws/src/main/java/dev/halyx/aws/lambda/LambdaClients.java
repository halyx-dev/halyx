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

import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.spi.ConnectFailures;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.retry.RetryUtils;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Lambda clients of one connection: one per retry strategy and HTTP client. Idempotent functions use the
 * connection's retry mode; the others retry only throttling and failures to connect, which Lambda rejects before
 * the function runs, so an SDK retry never runs a function twice. Functions whose timeout fits within the
 * connection's read timeout share its HTTP clients; a longer timeout gets HTTP clients of its own with that read
 * timeout, as the SDK can't lengthen a client's read timeout per request.
 */
public final class LambdaClients implements AutoCloseable {

    /**
     * Throttled or unconnected attempts that didn't run the function, tried again with the SDK's backoff. Without
     * {@code useClientDefaults(false)} the client adds its own conditions, which retry timeouts and 5xx.
     */
    static final ClientOverrideConfiguration BEFORE_SEND_RETRIES = ClientOverrideConfiguration.builder()
            .retryStrategy(StandardRetryStrategy.builder().retryOnException(LambdaClients::beforeSend)
                    .useClientDefaults(false).build())
            .build();

    private final Region region;
    private final AwsCredentialsProvider credentials;
    private final Optional<URI> endpoint;
    private final ClientOverrideConfiguration connectionRetries;
    private final Duration readTimeout;
    private final Supplier<SdkHttpClient> sharedHttp;
    private final Supplier<SdkAsyncHttpClient> sharedNettyHttp;
    private final Function<Duration, SdkHttpClient> newHttp;
    private final Function<Duration, SdkAsyncHttpClient> newNettyHttp;

    private final Map<Key, LambdaClient> clients = new HashMap<>();
    private final Map<Key, LambdaAsyncClient> asyncClients = new HashMap<>();
    private final Map<Duration, SdkHttpClient> ownHttp = new HashMap<>();
    private final Map<Duration, SdkAsyncHttpClient> ownNettyHttp = new HashMap<>();

    /**
     * @param connectionRetries the connection's overrides, with its retry mode, for idempotent functions
     * @param readTimeout       the shared HTTP clients' read timeout
     * @param newHttp           makes a blocking HTTP client with the connection's settings and the given read timeout
     * @param newNettyHttp      makes a non-blocking one
     */
    public LambdaClients(Region region, AwsCredentialsProvider credentials, Optional<URI> endpoint,
                         ClientOverrideConfiguration connectionRetries, Duration readTimeout,
                         Supplier<SdkHttpClient> sharedHttp, Supplier<SdkAsyncHttpClient> sharedNettyHttp,
                         Function<Duration, SdkHttpClient> newHttp, Function<Duration, SdkAsyncHttpClient> newNettyHttp) {
        this.region = region;
        this.credentials = credentials;
        this.endpoint = endpoint;
        this.connectionRetries = connectionRetries;
        this.readTimeout = readTimeout;
        this.sharedHttp = sharedHttp;
        this.sharedNettyHttp = sharedNettyHttp;
        this.newHttp = newHttp;
        this.newNettyHttp = newNettyHttp;
    }

    public synchronized LambdaClient client(FunctionConfig function) {
        return clients.computeIfAbsent(key(function), key -> {
            var builder = LambdaClient.builder().region(region).credentialsProvider(credentials)
                    .httpClient(key.ownTimeout() == null ? sharedHttp.get()
                            : ownHttp.computeIfAbsent(key.ownTimeout(), newHttp))
                    .overrideConfiguration(key.idempotent() ? connectionRetries : BEFORE_SEND_RETRIES);
            endpoint.ifPresent(builder::endpointOverride);
            return builder.build();
        });
    }

    /** The non-blocking client for {@code function}, made on its first asynchronous invocation. */
    public synchronized LambdaAsyncClient asyncClient(FunctionConfig function) {
        return asyncClients.computeIfAbsent(key(function), key -> {
            var builder = LambdaAsyncClient.builder().region(region).credentialsProvider(credentials)
                    .httpClient(key.ownTimeout() == null ? sharedNettyHttp.get()
                            : ownNettyHttp.computeIfAbsent(key.ownTimeout(), newNettyHttp))
                    .overrideConfiguration(key.idempotent() ? connectionRetries : BEFORE_SEND_RETRIES);
            endpoint.ifPresent(builder::endpointOverride);
            return builder.build();
        });
    }

    private Key key(FunctionConfig function) {
        return new Key(function.idempotent(), function.timeout().compareTo(readTimeout) > 0 ? function.timeout() : null);
    }

    /** Throttling, or an attempt that never reached Lambda. */
    static boolean beforeSend(Throwable error) {
        return error instanceof SdkException sdk && RetryUtils.isThrottlingException(sdk)
                || ConnectFailures.isConnectFailure(error);
    }

    @Override
    public synchronized void close() {
        List<AutoCloseable> open = new ArrayList<>(clients.values());
        open.addAll(asyncClients.values());
        open.addAll(ownHttp.values()); // clients never close an HTTP client they were given
        open.addAll(ownNettyHttp.values());
        clients.clear();
        asyncClients.clear();
        ownHttp.clear();
        ownNettyHttp.clear();
        for (AutoCloseable closeable : open) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // closing anyway
            }
        }
    }

    /** @param ownTimeout the read timeout of the client's own HTTP clients, or null to share the connection's */
    private record Key(boolean idempotent, Duration ownTimeout) {
    }
}
