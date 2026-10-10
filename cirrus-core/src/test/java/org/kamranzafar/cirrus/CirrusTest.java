package org.kamranzafar.cirrus;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.interceptor.Interceptor;
import org.kamranzafar.cirrus.interceptor.Invocation;
import org.kamranzafar.cirrus.interceptor.OperationContext;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CirrusTest {

    @Test
    void discoversInMemoryProviderFromClasspath() {
        try (Cirrus cirrus = Cirrus.builder().register("local", ProviderConfig.of("memory")).build()) {
            BlobStore store = cirrus.provider("local").blobStore("bucket");
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(store.provider()).isEqualTo("memory");
            assertThat(store.getBytes(BlobKey.of("k"))).asString().isEqualTo("v");
        }
    }

    @Test
    void namedRegistrationsAreIsolated() {
        try (Cirrus cirrus = Cirrus.builder()
                .register("primary", ProviderConfig.of("memory"))
                .register("archive", ProviderConfig.of("memory"))
                .build()) {
            cirrus.provider("primary").blobStore("b").put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(cirrus.provider("archive").blobStore("b").exists(BlobKey.of("k"))).isFalse();
            assertThat(cirrus.names()).containsExactly("primary", "archive");
        }
    }

    @Test
    void sameBucketReturnsSameClient() {
        try (Cirrus cirrus = Cirrus.builder().register("local", ProviderConfig.of("memory")).build()) {
            CloudContext context = cirrus.provider("local");
            assertThat(context.blobStore("b")).isSameAs(context.blobStore("b"));
        }
    }

    @Test
    void missingProviderPluginFailsFastWithHint() {
        assertThatThrownBy(() -> Cirrus.builder().register("x", ProviderConfig.of("nope")).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cirrus-nope")
                .hasMessageContaining("memory");
    }

    @Test
    void duplicateNamesAreRejected() {
        var builder = Cirrus.builder().register("x", ProviderConfig.of("memory"));
        assertThatThrownBy(() -> builder.register("x", ProviderConfig.of("memory")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownNameIsRejected() {
        try (Cirrus cirrus = Cirrus.builder().build()) {
            assertThatThrownBy(() -> cirrus.provider("missing")).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void closedContextRefusesNewClients() {
        Cirrus cirrus = Cirrus.builder().register("local", ProviderConfig.of("memory")).build();
        cirrus.close();
        assertThatThrownBy(() -> cirrus.provider("local").blobStore("b")).isInstanceOf(IllegalStateException.class);
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
        try (Cirrus cirrus = Cirrus.builder()
                .register("local", ProviderConfig.of("memory"))
                .interceptor(recorder)
                .build()) {
            BlobStore store = cirrus.provider("local").blobStore("b");
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));
            store.head(BlobKey.of("k"));
            store.delete(BlobKey.of("k"));
        }
        assertThat(seen).containsExactly("storage:put:b/k", "storage:head:b/k", "storage:delete:b/k");
    }
}
