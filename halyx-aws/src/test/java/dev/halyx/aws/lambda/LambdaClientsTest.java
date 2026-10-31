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
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Which Lambda client, and which HTTP client under it, each function configuration gets. */
class LambdaClientsTest {

    private final List<Duration> made = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final SdkHttpClient shared = http("shared");

    private SdkHttpClient http(String name) {
        return new SdkHttpClient() {
            @Override
            public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
                throw new UnsupportedOperationException("no requests in this test");
            }

            @Override
            public void close() {
                closed.add(name);
            }
        };
    }

    private final LambdaClients clients = new LambdaClients(Region.US_EAST_1, AnonymousCredentialsProvider.create(),
            Optional.empty(), ClientOverrideConfiguration.builder().build(), Duration.ofSeconds(60), () -> shared,
            () -> {
                throw new UnsupportedOperationException("blocking only");
            }, timeout -> {
                made.add(timeout);
                return http("own " + timeout);
            }, timeout -> {
                throw new UnsupportedOperationException("blocking only");
            });

    @Test
    void functionsWithinTheReadTimeoutShareTheConnectionsHttpClient() {
        assertThat(clients.client(FunctionConfig.of("a"))).isSameAs(clients.client(FunctionConfig.of("b")))
                .isSameAs(clients.client(FunctionConfig.builder("c").timeout(Duration.ofSeconds(60)).build()));
        assertThat(made).isEmpty();
    }

    @Test
    void idempotentFunctionsHaveTheirOwnClientForTheirRetryStrategy() {
        assertThat(clients.client(FunctionConfig.builder("a").idempotent(true).build()))
                .isNotSameAs(clients.client(FunctionConfig.of("a")))
                .isSameAs(clients.client(FunctionConfig.builder("b").idempotent(true).build()));
        assertThat(made).isEmpty();
    }

    @Test
    void aLongerTimeoutGetsAnHttpClientOfItsOwn() {
        FunctionConfig fiveMinutes = FunctionConfig.builder("report").timeout(Duration.ofMinutes(5)).build();

        assertThat(clients.client(fiveMinutes)).isNotSameAs(clients.client(FunctionConfig.of("report")))
                .isSameAs(clients.client(FunctionConfig.builder("other").timeout(Duration.ofMinutes(5)).build()));
        clients.client(FunctionConfig.builder("report").timeout(Duration.ofMinutes(5)).idempotent(true).build());
        assertThat(made).containsExactly(Duration.ofMinutes(5));
    }

    @Test
    void closingClosesOnlyItsOwnHttpClients() {
        clients.client(FunctionConfig.of("a"));
        clients.client(FunctionConfig.builder("b").timeout(Duration.ofMinutes(5)).build());

        clients.close();

        assertThat(closed).containsExactly("own PT5M");
    }
}
