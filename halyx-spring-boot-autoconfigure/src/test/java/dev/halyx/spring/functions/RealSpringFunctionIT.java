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
package dev.halyx.spring.functions;

import dev.halyx.error.UnavailableException;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.json.Json;
import dev.halyx.reactor.ReactiveFunctionInvoker;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Boot with a real cloud's function (tier 1): a {@code FunctionInvoker} bean from {@code halyx.functions},
 * with configured headers, and its reactive view, on Boot 3.5 and 4. Runs only with
 * {@code -Dhalyx.real.spring.functions=true}, from the real-cloud scripts' tier 1, which pass the provider type and
 * properties, and the run's test function ({@code halyx.real.spring.function}, with {@code .function-properties}).
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.functions", matches = "true")
class RealSpringFunctionIT {

    private static void addAll(List<String> properties, String prefix, String pairs) {
        for (String pair : pairs.split(",")) {
            if (!pair.isBlank()) {
                properties.add(prefix + pair.trim());
            }
        }
    }

    private ApplicationContextRunner runner() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.functions.tck.name=" + System.getProperty("halyx.real.spring.function"),
                "halyx.functions.tck.timeout=90s",
                "halyx.functions.tck.headers.X-Halyx-App=spring"));
        addAll(properties, "halyx.providers.cloud.properties.", System.getProperty("halyx.real.spring.provider-properties", ""));
        addAll(properties, "halyx.functions.tck.properties.", System.getProperty("halyx.real.spring.function-properties", ""));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theInvokerBeanCallsTheFunctionWithItsHeaders() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            FunctionInvoker tck = context.getBean("tck", FunctionInvoker.class);

            Map<String, Object> echoed = (Map<String, Object>) Json.parse(tck.invoke(FunctionRequest.builder(
                    "{\"action\":\"echo\",\"order\":\"o-42\"}").header("X-Halyx-Request", "request").build()).payloadAsString());

            assertThat(echoed.get("body")).isEqualTo(Map.of("action", "echo", "order", "o-42"));
            Map<String, String> headers = new HashMap<>();
            ((Map<String, Object>) echoed.get("headers")).forEach(
                    (name, value) -> headers.put(name.toLowerCase(Locale.ROOT), String.valueOf(value)));
            assertThat(headers).containsEntry("x-halyx-app", "spring").containsEntry("x-halyx-request", "request");
            assertThatThrownBy(() -> tck.invoke(FunctionRequest.of("{\"action\":\"fail\",\"message\":\"spring\"}")))
                    .isInstanceOf(UnavailableException.class);
        });
    }

    @Test
    void theReactiveViewInvokesTheFunction() {
        runner().run(context -> {
            ReactiveFunctionInvoker tck = context.getBean("tckReactive", ReactiveFunctionInvoker.class);

            String body = tck.invoke(FunctionRequest.of("{\"action\":\"echo\"}")).block().payloadAsString();

            assertThat(Json.at(Json.parse(body), "body", "action")).isEqualTo("echo");
        });
    }
}
