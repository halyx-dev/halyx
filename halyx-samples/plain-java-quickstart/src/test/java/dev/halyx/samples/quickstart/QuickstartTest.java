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
package dev.halyx.samples.quickstart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;

import org.junit.jupiter.api.Test;

class QuickstartTest {

    @Test
    void writesAndReadsABlobAMessageAndAParameter() {
        try (var halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build()) {
            Quickstart.Result result = Quickstart.run(halyx.provider("test"));

            assertThat(result.blob()).isEqualTo("hello, blob");
            assertThat(result.message()).isEqualTo("hello, message");
            assertThat(result.parameter()).isEqualTo("hello, parameter");
        }
    }
}
