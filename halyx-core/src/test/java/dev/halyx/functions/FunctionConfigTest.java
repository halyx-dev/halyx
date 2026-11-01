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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionConfigTest {

    @Test
    void aSuppliedPropertyIsAskedEachTime() {
        AtomicReference<String> key = new AtomicReference<>("first");
        FunctionConfig config = FunctionConfig.builder("resize-image").property("azure.functions.key", key::get).build();

        assertThat(config.property("azure.functions.key")).hasValue("first");
        key.set("rotated");
        assertThat(config.property("azure.functions.key")).hasValue("rotated");
        assertThat(config.propertySupplier("azure.functions.key")).hasValueSatisfying(s -> assertThat(s.get())
                .isEqualTo("rotated"));
        assertThat(config.properties()).isEmpty();
        assertThat(config.suppliedProperties()).containsOnlyKeys("azure.functions.key");
    }

    @Test
    void aFixedPropertyIsItsOwnSupplier() {
        FunctionConfig config = FunctionConfig.builder("resize-image").property("azure.functions.key", "fixed").build();

        assertThat(config.propertySupplier("azure.functions.key")).hasValueSatisfying(s -> assertThat(s.get())
                .isEqualTo("fixed"));
        assertThat(config.propertySupplier("azure.functions.audience")).isEmpty();
    }

    @Test
    void theLastSettingOfAPropertyWins() {
        FunctionConfig supplied = FunctionConfig.builder("f").property("k", "fixed").property("k", () -> "supplied").build();
        FunctionConfig fixed = FunctionConfig.builder("f").property("k", () -> "supplied").property("k", "fixed").build();

        assertThat(supplied.property("k")).hasValue("supplied");
        assertThat(supplied.properties()).isEmpty();
        assertThat(fixed.property("k")).hasValue("fixed");
        assertThat(fixed.suppliedProperties()).isEmpty();
    }
}
