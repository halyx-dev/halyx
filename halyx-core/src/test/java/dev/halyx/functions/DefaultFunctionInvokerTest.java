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
package dev.halyx.functions;

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.capability.Capabilities;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.spi.AbstractFunctionInvokerDriver;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.memory.InMemoryFunctions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFunctionInvokerTest {

    private final List<OperationContext> operations = new ArrayList<>();
    private Halyx halyx;
    private CloudContext context;

    @BeforeEach
    void start() {
        Interceptor recording = new Interceptor() {
            @Override
            public <T> T intercept(OperationContext operation, Invocation<T> next) {
                synchronized (operations) {
                    operations.add(operation);
                }
                return next.proceed();
            }
        };
        halyx = Halyx.builder().interceptor(recording).register("local", ProviderConfig.of("memory")).build();
        context = halyx.provider("local");
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    private FunctionInvoker echo(FunctionConfig config) {
        FunctionInvoker invoker = context.functionInvoker(config);
        invoker.unwrap(InMemoryFunctions.class).register(config.name(), request -> FunctionResponse
                .builder(request.payload()).contentType(request.contentType()).headers(request.headers()).build());
        return invoker;
    }

    @Test
    void invokesTheRegisteredHandler() {
        FunctionInvoker resize = echo(FunctionConfig.of("resize-image"));

        FunctionResponse response = resize.invoke(FunctionRequest.of("{\"key\":\"a.png\"}"));

        assertThat(response.payloadAsString()).isEqualTo("{\"key\":\"a.png\"}");
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(resize.provider()).isEqualTo("memory");
        assertThat(resize.function()).isEqualTo("resize-image");
    }

    @Test
    void requestHeadersGoOnTopOfTheConfiguredOnes() {
        FunctionInvoker invoker = echo(FunctionConfig.builder("headers")
                .header("X-App", "orders").header("X-Tenant", "default").build());

        FunctionResponse response = invoker.invoke(FunctionRequest.builder("{}").header("X-Tenant", "acme")
                .header("X-Request", "r-1").contentType("text/plain").build());

        assertThat(response.headers()).containsExactlyInAnyOrderEntriesOf(
                Map.of("X-App", "orders", "X-Tenant", "acme", "X-Request", "r-1"));
        assertThat(response.contentType()).isEqualTo("text/plain");
    }

    @Test
    void payloadsOverTheLimitAreRejectedBeforeSending() {
        FunctionInvoker invoker = echo(FunctionConfig.of("big"));
        byte[] overInvoke = new byte[6 * 1024 * 1024 + 1];
        byte[] overTrigger = new byte[1024 * 1024 + 1];

        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of(overInvoke)))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> invoker.trigger(FunctionRequest.of(overTrigger)))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("limit");
        assertThat(invoker.invoke(FunctionRequest.of(overTrigger)).payloadUnsafe()).hasSize(overTrigger.length);
        assertThat(operations).extracting(OperationContext::operation).containsExactly("invoke");
    }

    @Test
    void headersOverTheLimitAreRejectedBeforeSending() {
        FunctionInvoker invoker = echo(FunctionConfig.builder("headers").header("X-Big", "x".repeat(3_000)).build());

        assertThatThrownBy(() -> invoker.invoke(FunctionRequest.builder("{}").header("X-More", "y".repeat(600)).build()))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("headers");
        assertThat(operations).isEmpty();
    }

    @Test
    void triggerNeedsTheCapability() {
        FunctionInvoker invoker = new DefaultFunctionInvoker(new SyncOnly(Capabilities.none()), FunctionConfig.of("sync"),
                InterceptorChain.empty());

        assertThatThrownBy(() -> invoker.trigger(FunctionRequest.of("{}"))).isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("TRIGGER");
        assertThat(invoker.async().trigger(FunctionRequest.of("{}")))
                .failsWithin(Duration.ofSeconds(5)).withThrowableThat().withCauseInstanceOf(UnsupportedFeatureException.class);
    }

    @Test
    void triggerHeadersNeedTheirCapability() {
        SyncOnly driver = new SyncOnly(Capabilities.of(FunctionCapability.TRIGGER));
        FunctionInvoker plain = new DefaultFunctionInvoker(driver, FunctionConfig.of("event"), InterceptorChain.empty());
        FunctionInvoker withHeaders = new DefaultFunctionInvoker(driver,
                FunctionConfig.builder("event").header("X-App", "orders").build(), InterceptorChain.empty());

        plain.trigger(FunctionRequest.of("{}"));
        assertThat(driver.triggered).hasSize(1);
        assertThatThrownBy(() -> plain.trigger(FunctionRequest.builder("{}").header("X-Tenant", "acme").build()))
                .isInstanceOf(UnsupportedFeatureException.class).hasMessageContaining("X-Tenant");
        assertThatThrownBy(() -> withHeaders.trigger(FunctionRequest.of("{}")))
                .isInstanceOf(UnsupportedFeatureException.class).hasMessageContaining("X-App");
        assertThat(driver.triggered).hasSize(1);
    }

    @Test
    void retriesFollowTheConfiguration() {
        echo(FunctionConfig.of("once")).invoke(FunctionRequest.of("{}"));
        echo(FunctionConfig.builder("repeatable").idempotent(true).build()).invoke(FunctionRequest.of("{}"));

        assertThat(operations).extracting(OperationContext::service, OperationContext::resource, OperationContext::idempotent)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("functions", "once", false),
                        org.assertj.core.groups.Tuple.tuple("functions", "repeatable", true));
    }

    @Test
    void asyncInvokesWithTheSameChecksAndErrors() {
        FunctionInvoker invoker = echo(FunctionConfig.builder("async").header("X-App", "orders").build());

        FunctionResponse response = invoker.async().invoke(FunctionRequest.of("{\"n\":1}")).join();
        assertThat(response.payloadAsString()).isEqualTo("{\"n\":1}");
        assertThat(response.headers()).containsEntry("X-App", "orders");
        assertThat(invoker.async()).isSameAs(invoker.async());
        assertThat(invoker.async().blocking()).isSameAs(invoker);

        assertThatThrownBy(() -> context.functionInvoker("missing").async().invoke(FunctionRequest.of("{}")).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(NotFoundException.class);
        assertThat(invoker.async().invoke(FunctionRequest.of(new byte[6 * 1024 * 1024 + 1])))
                .failsWithin(Duration.ofSeconds(5)).withThrowableThat().withCauseInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void configurationsWithTheSameSettingsAreEqual() {
        FunctionConfig config = FunctionConfig.builder("resize").timeout(Duration.ofSeconds(10)).header("X-App", "orders")
                .idempotent(true).property("aws.lambda.qualifier", "live").build();

        assertThat(config).isEqualTo(FunctionConfig.builder("resize").timeout(Duration.ofSeconds(10)).header("X-App", "orders")
                .idempotent(true).property("aws.lambda.qualifier", "live").build());
        assertThat(config).isNotEqualTo(FunctionConfig.builder("resize").timeout(Duration.ofSeconds(10))
                .header("X-App", "orders").property("aws.lambda.qualifier", "live").build());
    }

    @Test
    void configurationDefaultsAndValidation() {
        FunctionConfig config = FunctionConfig.builder("f").property("azure.functions.key", "").build();

        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.idempotent()).isFalse();
        assertThat(config.headers()).isEmpty();
        assertThat(config.property("azure.functions.key")).isEmpty();
        assertThatThrownBy(() -> FunctionConfig.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FunctionConfig.builder("f").timeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withPayloadKeepsTheContentTypeAndAddsHeaders() {
        FunctionRequest request = FunctionRequest.builder("a").contentType("text/plain").header("X-A", "1").build();

        FunctionRequest copy = request.withPayload("bb".getBytes(), Map.of("X-A", "2", "X-B", "3"));

        assertThat(copy.payloadAsString()).isEqualTo("bb");
        assertThat(copy.contentType()).isEqualTo("text/plain");
        assertThat(copy.headers()).containsExactlyInAnyOrderEntriesOf(Map.of("X-A", "2", "X-B", "3"));
        assertThat(request.headers()).containsExactlyEntriesOf(Map.of("X-A", "1"));
    }

    /** A driver with only the given capabilities, recording what it was asked to trigger. */
    private static final class SyncOnly extends AbstractFunctionInvokerDriver {

        private final Capabilities capabilities;
        final List<FunctionRequest> triggered = new ArrayList<>();

        SyncOnly(Capabilities capabilities) {
            super("fake");
            this.capabilities = capabilities;
        }

        @Override
        public String function() {
            return "sync";
        }

        @Override
        public FunctionResponse invoke(FunctionRequest request) {
            return FunctionResponse.of(request.payload());
        }

        @Override
        public void trigger(FunctionRequest request) {
            triggered.add(request);
        }

        @Override
        public Capabilities capabilities() {
            return capabilities;
        }

        @Override
        public long maxPayloadBytes() {
            return 1024;
        }

        @Override
        protected Optional<CloudException> translateNative(Throwable error) {
            return Optional.empty();
        }

        @Override
        protected List<Object> nativeClients() {
            return List.of();
        }
    }
}
