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
package org.kamranzafar.halyx;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.interceptor.Interceptor;
import org.kamranzafar.halyx.interceptor.Invocation;
import org.kamranzafar.halyx.interceptor.OperationContext;
import org.kamranzafar.halyx.storage.BlobContent;
import org.kamranzafar.halyx.storage.BlobKey;
import org.kamranzafar.halyx.storage.BlobStore;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HalyxTest {

    @Test
    void discoversInMemoryProviderFromClasspath() {
        try (Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build()) {
            BlobStore store = halyx.provider("local").blobStore("bucket");
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(store.provider()).isEqualTo("memory");
            assertThat(store.getBytes(BlobKey.of("k"))).asString().isEqualTo("v");
        }
    }

    @Test
    void namedRegistrationsAreIsolated() {
        try (Halyx halyx = Halyx.builder()
                .register("primary", ProviderConfig.of("memory"))
                .register("archive", ProviderConfig.of("memory"))
                .build()) {
            halyx.provider("primary").blobStore("b").put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(halyx.provider("archive").blobStore("b").exists(BlobKey.of("k"))).isFalse();
            assertThat(halyx.names()).containsExactly("primary", "archive");
        }
    }

    @Test
    void sameBucketReturnsSameClient() {
        try (Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build()) {
            CloudContext context = halyx.provider("local");
            assertThat(context.blobStore("b")).isSameAs(context.blobStore("b"));
        }
    }

    @Test
    void missingProviderPluginFailsFastWithHint() {
        assertThatThrownBy(() -> Halyx.builder().register("x", ProviderConfig.of("nope")).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("halyx-nope")
                .hasMessageContaining("memory");
    }

    @Test
    void duplicateNamesAreRejected() {
        var builder = Halyx.builder().register("x", ProviderConfig.of("memory"));
        assertThatThrownBy(() -> builder.register("x", ProviderConfig.of("memory")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownNameIsRejected() {
        try (Halyx halyx = Halyx.builder().build()) {
            assertThatThrownBy(() -> halyx.provider("missing")).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void closedContextRefusesNewClients() {
        Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build();
        halyx.close();
        assertThatThrownBy(() -> halyx.provider("local").blobStore("b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void interceptorsSeeEveryOperationInOrder() {
        List<String> seen = new ArrayList<>();
        Interceptor recorder = new Interceptor() {
            @Override
            public <T> T intercept(OperationContext context, Invocation<T> next) {
                seen.add(context.service() + ":" + context.operation() + ":" + context.resource());
                return next.proceed();
            }
        };
        try (Halyx halyx = Halyx.builder()
                .register("local", ProviderConfig.of("memory"))
                .interceptor(recorder)
                .build()) {
            BlobStore store = halyx.provider("local").blobStore("b");
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));
            store.head(BlobKey.of("k"));
            store.delete(BlobKey.of("k"));
        }
        assertThat(seen).containsExactly("storage:put:b/k", "storage:head:b/k", "storage:delete:b/k");
    }
}
