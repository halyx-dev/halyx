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
package dev.halyx.reactor;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnavailableException;
import dev.halyx.functions.FunctionCapability;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.memory.InMemoryFunctions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Function invocations as {@code Mono}s. */
class ReactiveFunctionInvokerTest {

    private Halyx halyx;
    private ReactiveHalyx rx;
    private final AtomicInteger invocations = new AtomicInteger();

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
        halyx.provider("mem").functionInvoker("echo").unwrap(InMemoryFunctions.class).register("echo", request -> {
            invocations.incrementAndGet();
            return FunctionResponse.of(request.payload());
        });
    }

    @AfterEach
    void stop() {
        rx.close();
        halyx.close();
    }

    @Test
    void invokesOnlyWhenSubscribedAndAgainOnEachSubscription() {
        ReactiveFunctionInvoker echo = rx.provider("mem").functionInvoker("echo");
        Mono<FunctionResponse> invoked = echo.invoke(FunctionRequest.of("{\"n\":1}"));

        assertThat(invocations).hasValue(0);
        StepVerifier.create(invoked.map(FunctionResponse::payloadAsString)).expectNext("{\"n\":1}").verifyComplete();
        StepVerifier.create(invoked.repeat(1)).expectNextCount(2).verifyComplete();

        assertThat(invocations).hasValue(3);
        assertThat(echo.function()).isEqualTo("echo");
        assertThat(echo.blocking()).isSameAs(halyx.provider("mem").functionInvoker("echo"));
    }

    @Test
    void resultsArriveOnTheGivenSchedulerAndErrorsAreTheBlockingOnes() {
        Scheduler scheduler = ReactiveHalyx.virtualThreadScheduler("app-calls");
        try (ReactiveHalyx own = ReactiveHalyx.of(halyx, scheduler)) {
            String thread = own.provider("mem").functionInvoker("echo").invoke(FunctionRequest.of("{}"))
                    .map(r -> Thread.currentThread().getName()).block();
            assertThat(thread).startsWith("app-calls-");

            StepVerifier.create(own.provider("mem").functionInvoker("missing").invoke(FunctionRequest.of("{}")))
                    .verifyError(NotFoundException.class);
        }
        scheduler.dispose();
    }

    @Test
    void aFunctionThatThrowsFailsTheMono() {
        ReactiveFunctionInvoker broken = rx.provider("mem").functionInvoker("broken");
        broken.unwrap(InMemoryFunctions.class).register("broken", request -> {
            throw new IllegalStateException("disk full");
        });

        StepVerifier.create(broken.invoke(FunctionRequest.of("{}"))).verifyError(UnavailableException.class);
    }

    @Test
    void triggerCompletesOnceAccepted() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        ReactiveFunctionInvoker audit = rx.provider("mem").functionInvoker("audit");
        audit.unwrap(InMemoryFunctions.class).register("audit", request -> {
            ran.countDown();
            return FunctionResponse.of("");
        });

        assertThat(audit.capabilities().supports(FunctionCapability.TRIGGER)).isTrue();
        StepVerifier.create(audit.trigger(FunctionRequest.of("{}"))).verifyComplete();
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cancellingBeforeTheResultDropsIt() {
        CountDownLatch release = new CountDownLatch(1);
        ReactiveFunctionInvoker slow = rx.provider("mem").functionInvoker("slow");
        slow.unwrap(InMemoryFunctions.class).register("slow", request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FunctionResponse.of("late");
        });

        StepVerifier.create(slow.invoke(FunctionRequest.of("{}")))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(100))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
        release.countDown();
    }
}
