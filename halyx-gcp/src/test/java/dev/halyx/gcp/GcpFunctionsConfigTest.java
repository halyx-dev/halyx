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
package dev.halyx.gcp;

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.functions.FunctionConfig;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the connection configures a Cloud Run function: its URL and its login. */
class GcpFunctionsConfigTest {

    private static void withProvider(ProviderConfig config, Consumer<CloudContext> test) {
        try (Halyx halyx = Halyx.builder().register("gcp", config).build()) {
            test.accept(halyx.provider("gcp"));
        }
    }

    @Test
    void theDefaultUrlNeedsTheRegion() {
        withProvider(ProviderConfig.of("gcp").credentials(Credentials.anonymous()).property("gcp.project-id", "orders"),
                gcp -> {
                    assertThatThrownBy(() -> gcp.functionInvoker("resize-image")).isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("gcp.functions.region").hasMessageContaining("gcp.functions.url");
                    assertThat(gcp.functionInvoker(FunctionConfig.builder("resize-image")
                            .property("gcp.functions.region", "europe-west2").build()).function()).isEqualTo("resize-image");
                });
    }

    @Test
    void aUrlNeedsNoRegionOrProject() {
        withProvider(ProviderConfig.of("gcp").credentials(Credentials.anonymous()), gcp -> assertThat(gcp.functionInvoker(
                FunctionConfig.builder("resize-image").property("gcp.functions.url", "https://resize-image-abc.a.run.app")
                        .build()).function()).isEqualTo("resize-image"));
    }

    @Test
    void anInvalidUrlIsRefused() {
        withProvider(ProviderConfig.of("gcp").credentials(Credentials.anonymous()), gcp -> assertThatThrownBy(() ->
                gcp.functionInvoker(FunctionConfig.builder("resize-image").property("gcp.functions.url", "not a url").build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a url"));
    }

    @Test
    void idTokensNeedAGoogleIdentity() {
        withProvider(ProviderConfig.of("gcp").credentials(Credentials.of("key", "secret")), gcp -> assertThatThrownBy(() ->
                gcp.functionInvoker(FunctionConfig.builder("resize-image")
                        .property("gcp.functions.url", "https://resize-image-abc.a.run.app").build()))
                .isInstanceOf(IllegalArgumentException.class));
    }
}
