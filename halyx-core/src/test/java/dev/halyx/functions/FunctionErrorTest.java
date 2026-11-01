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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnavailableException;
import dev.halyx.memory.InMemoryFunctions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionErrorTest {

    @Test
    void isFoundAnywhereInTheCauses() {
        FunctionError reported = FunctionError.http(400, "{\"error\":\"unknown zone\"}".getBytes(StandardCharsets.UTF_8));
        UnavailableException failure = new UnavailableException("Function f returned 400", reported, "test", "400");

        assertThat(FunctionError.of(failure)).containsSame(reported);
        assertThat(FunctionError.of(new CompletionException(failure))).containsSame(reported);
        assertThat(FunctionError.of(new IllegalStateException("not from a function"))).isEmpty();
        assertThat(FunctionError.of(null)).isEmpty();
    }

    @Test
    void anHttpErrorKeepsItsStatusAndBody() {
        byte[] body = "{\"error\":\"unknown zone\"}".getBytes(StandardCharsets.UTF_8);
        FunctionError reported = FunctionError.http(400, body);
        body[0] = 'x';

        assertThat(reported.status()).hasValue(400);
        assertThat(reported.message()).isEqualTo("{\"error\":\"unknown zone\"}");
        assertThat(reported.body()).isEqualTo("{\"error\":\"unknown zone\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(reported.type()).isEmpty();
        assertThat(reported.getStackTrace()).isEmpty();
    }

    @Test
    void aMemoryHandlerThatThrowsKeepsItsExceptionBehindTheFunctionError() {
        IllegalStateException thrown = new IllegalStateException("unknown zone");
        try (Halyx halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build()) {
            FunctionInvoker invoker = halyx.provider("test").functionInvoker("quote");
            invoker.unwrap(InMemoryFunctions.class).register("quote", request -> {
                throw thrown;
            });

            assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}")))
                    .isInstanceOfSatisfying(UnavailableException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("Function quote failed: unknown zone");
                        assertThat(FunctionError.of(e)).hasValueSatisfying(reported -> {
                            assertThat(reported.message()).isEqualTo("unknown zone");
                            assertThat(reported.type()).contains(IllegalStateException.class.getName());
                            assertThat(reported.getCause()).isSameAs(thrown);
                        });
                    });
        }
    }

    @Test
    void aMemoryHandlerThatThrowsACloudExceptionPassesItThrough() {
        try (Halyx halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build()) {
            FunctionInvoker invoker = halyx.provider("test").functionInvoker("quote");
            invoker.unwrap(InMemoryFunctions.class).register("quote", request -> {
                throw new UnavailableException("busy", null, "memory", null);
            });

            assertThatThrownBy(() -> invoker.invoke(FunctionRequest.of("{}"))).isInstanceOf(CloudException.class)
                    .hasMessage("busy").satisfies(e -> assertThat(FunctionError.of(e)).isEmpty());
        }
    }
}
