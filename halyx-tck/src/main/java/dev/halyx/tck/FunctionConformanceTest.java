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
package dev.halyx.tck;

import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.functions.FunctionCapability;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for function invocation: one test function, deployed in the environment under test, invoked
 * through the provider's invoker. The function takes a JSON object whose {@code action} says what to do:
 *
 * <ul>
 *   <li>{@code echo}: returns {@code {"body": <the request payload, as JSON>, "headers": {<name>: <value>}}}, with
 *       every header it received (on Lambda, the {@code ClientContext}'s {@code custom} map)</li>
 *   <li>{@code fail}: throws an exception with the request's {@code message}</li>
 *   <li>{@code status}: answers with the HTTP status {@code status} (HTTP functions only)</li>
 *   <li>{@code sleep}: adds one to the count for {@code id}, waits {@code millis} milliseconds, then returns
 *       {@code {}}</li>
 *   <li>{@code count}: adds one to the count for {@code id}, then throws if {@code fail} is true</li>
 *   <li>{@code read}: returns {@code {"count": <the count for id>}}</li>
 * </ul>
 *
 * Counts may be kept in the function's memory, so the environment must serve the function from one instance, such as
 * with a concurrency or instance limit of one. A function busy with another invocation may then be throttled, so
 * reads of a count wait for it.
 */
public abstract class FunctionConformanceTest {

    /** The test function, with whatever provider properties reach it; tests add timeouts, headers and so on. */
    protected abstract FunctionConfig.Builder function();

    /** A function that isn't deployed, configured as {@link #function()} is. */
    protected abstract FunctionConfig.Builder missingFunction();

    /** The invoker for {@code config}, from the provider under test. */
    protected abstract FunctionInvoker invoker(FunctionConfig config);

    /** The largest payload the provider takes on {@code invoke}. */
    protected abstract long maxPayloadBytes();

    /** The largest headers the provider checks before sending; {@code Long.MAX_VALUE} where the cloud checks. */
    protected long maxHeaderBytes() {
        return Long.MAX_VALUE;
    }

    /** Whether the function can answer with an HTTP status: not on Lambda, whose functions return only a payload. */
    protected boolean returnsHttpStatuses() {
        return true;
    }

    /** How long a triggered function may take to run; real clouds need longer than emulators. */
    protected Duration timeout() {
        return Duration.ofSeconds(30);
    }

    /** Tests to skip because the environment does not implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void skipKnownGaps(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
    }

    private FunctionInvoker invoker() {
        return invoker(function().build());
    }

    private static FunctionRequest action(String action, Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.putAll(fields);
        return FunctionRequest.of(Json.write(payload));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(FunctionResponse response) {
        return (Map<String, Object>) Json.parse(response.payloadAsString());
    }

    /** The headers the echo function received, by lower-case name: HTTP header names aren't case-sensitive. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> headersSeen(FunctionResponse response) {
        Map<String, String> seen = new LinkedHashMap<>();
        ((Map<String, Object>) json(response).get("headers")).forEach(
                (name, value) -> seen.put(name.toLowerCase(Locale.ROOT), String.valueOf(value)));
        return seen;
    }

    /** The count for {@code id}, once the function's one instance is free to answer. */
    private long count(FunctionInvoker invoker, String id) {
        Instant deadline = Instant.now().plus(timeout());
        while (true) {
            try {
                return ((Number) json(invoker.invoke(action("read", Map.of("id", id)))).get("count")).longValue();
            } catch (ThrottledException busy) {
                if (Instant.now().isAfter(deadline)) {
                    throw busy;
                }
                pause(Duration.ofMillis(500));
            }
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void echoReturnsThePayloadIntact() {
        String data = "x".repeat(256 * 1024);
        FunctionRequest request = action("echo", Map.of("data", data, "text", "naïve café 日本"));

        FunctionResponse response = invoker().invoke(request);

        // Compared as JSON: Lambda hands the function its payload parsed, so it can't echo the same bytes.
        assertThat(json(response).get("body")).isEqualTo(Json.parse(request.payloadAsString()));
        assertThat(response.contentType()).startsWith("application/json");
    }

    @Test
    void configuredAndPerRequestHeadersReachTheFunction() {
        FunctionInvoker invoker = invoker(function().header("X-Halyx-Configured", "configured")
                .header("X-Halyx-Both", "configured").build());

        FunctionResponse response = invoker.invoke(FunctionRequest.builder(Json.write(Map.of("action", "echo")))
                .header("X-Halyx-Request", "request").header("X-Halyx-Both", "request").build());

        assertThat(headersSeen(response)).containsEntry("x-halyx-configured", "configured")
                .containsEntry("x-halyx-request", "request")
                .containsEntry("x-halyx-both", "request");
    }

    @Test
    void aFunctionThatThrowsIsUnavailable() {
        assertThatThrownBy(() -> invoker().invoke(action("fail", Map.of("message", "halyx-tck failure"))))
                .isInstanceOf(UnavailableException.class)
                .satisfies(e -> assertThat(((CloudException) e).providerErrorCode()).isPresent());
    }

    @Test
    void statusesMapToTheirErrors() {
        assumeTrue(returnsHttpStatuses(), "the function can't answer with an HTTP status");
        FunctionInvoker invoker = invoker();

        assertThatThrownBy(() -> invoker.invoke(action("status", Map.of("status", 400))))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> invoker.invoke(action("status", Map.of("status", 404))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aMissingFunctionIsNotFound() {
        assertThatThrownBy(() -> invoker(missingFunction().build()).invoke(action("echo", Map.of())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void payloadsOverTheLimitAreRefusedBeforeSending() {
        byte[] over = new byte[Math.toIntExact(maxPayloadBytes() + 1)];

        assertThatThrownBy(() -> invoker().invoke(FunctionRequest.of(over))).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining(String.valueOf(maxPayloadBytes()));
    }

    @Test
    void headersOverTheLimitAreRefusedBeforeSending() {
        assumeTrue(maxHeaderBytes() < Long.MAX_VALUE, "the cloud checks headers itself");
        FunctionRequest request = FunctionRequest.builder(Json.write(Map.of("action", "echo")))
                .header("X-Halyx-Big", "x".repeat(Math.toIntExact(maxHeaderBytes()))).build();

        assertThatThrownBy(() -> invoker().invoke(request)).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aSlowFunctionTimesOut() {
        FunctionInvoker invoker = invoker(function().timeout(Duration.ofSeconds(2)).build());
        invoker.invoke(action("echo", Map.of())); // started, so a cold start doesn't count towards the timeout

        String id = UUID.randomUUID().toString();
        Instant start = Instant.now();
        assertThatThrownBy(() -> invoker.invoke(action("sleep", Map.of("id", id, "millis", 6_000))))
                .isInstanceOf(UnavailableException.class);
        // Back before the sleep could have finished, so neither waited for it nor sent again
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
        // A non-idempotent function isn't sent again after it timed out: it may still be running.
        assertThat(count(invoker, id)).as("runs of the timed-out invocation").isEqualTo(1);
    }

    @Test
    void aFailedInvocationOfANonIdempotentFunctionRunsOnce() {
        FunctionInvoker invoker = invoker();
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> invoker.invoke(action("count", Map.of("id", id, "fail", true))))
                .isInstanceOf(UnavailableException.class);

        assertThat(count(invoker, id)).as("runs of the failed invocation").isEqualTo(1);
    }

    @Test
    void asyncInvocationsHaveTheSameResultsAndErrors() {
        FunctionRequest request = action("echo", Map.of("n", 1));

        FunctionResponse response = invoker().async().invoke(request).join();

        assertThat(json(response).get("body")).isEqualTo(Json.parse(request.payloadAsString()));
        assertThatThrownBy(() -> invoker(missingFunction().build()).async().invoke(action("echo", Map.of())).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(NotFoundException.class);
    }

    @Test
    void triggerRunsTheFunctionWhereDeclared() {
        FunctionInvoker invoker = invoker();
        String id = UUID.randomUUID().toString();
        if (!invoker.capabilities().supports(FunctionCapability.TRIGGER)) {
            assertThatThrownBy(() -> invoker.trigger(action("count", Map.of("id", id))))
                    .isInstanceOf(UnsupportedFeatureException.class);
            return;
        }

        invoker.trigger(action("count", Map.of("id", id)));

        awaitCount(invoker, id);
    }

    @Test
    void triggerHeadersWhereDeclared() {
        FunctionInvoker invoker = invoker();
        assumeTrue(invoker.capabilities().supports(FunctionCapability.TRIGGER), "no trigger");
        String id = UUID.randomUUID().toString();
        FunctionRequest request = FunctionRequest.builder(Json.write(Map.of("action", "count", "id", id)))
                .header("X-Halyx-Request", "request").build();
        if (!invoker.capabilities().supports(FunctionCapability.TRIGGER_HEADERS)) {
            assertThatThrownBy(() -> invoker.trigger(request)).isInstanceOf(UnsupportedFeatureException.class);
            return;
        }

        invoker.trigger(request);

        awaitCount(invoker, id);
    }

    private void awaitCount(FunctionInvoker invoker, String id) {
        Instant deadline = Instant.now().plus(timeout());
        while (count(invoker, id) < 1 && Instant.now().isBefore(deadline)) {
            pause(Duration.ofMillis(200));
        }
        assertThat(count(invoker, id)).as("runs of the triggered invocation").isEqualTo(1);
    }
}
