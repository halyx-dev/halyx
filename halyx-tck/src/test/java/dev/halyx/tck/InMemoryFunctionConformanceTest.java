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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Runs the function kit against the in-memory provider, which also checks the kit itself. */
class InMemoryFunctionConformanceTest extends FunctionConformanceTest {

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        TckFunction.register(halyx.provider("test"), "tck");
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Override
    protected FunctionConfig.Builder function() {
        return FunctionConfig.builder("tck");
    }

    @Override
    protected FunctionConfig.Builder missingFunction() {
        return FunctionConfig.builder("tck-missing");
    }

    @Override
    protected FunctionInvoker invoker(FunctionConfig config) {
        return halyx.provider("test").functionInvoker(config);
    }

    @Override
    protected long maxPayloadBytes() {
        return 6L * 1024 * 1024;
    }

    @Override
    protected long maxHeaderBytes() {
        return 3_583;
    }
}
