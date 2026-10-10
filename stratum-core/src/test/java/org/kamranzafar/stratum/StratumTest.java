package org.kamranzafar.stratum;

import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.interceptor.Interceptor;
import org.kamranzafar.stratum.interceptor.Invocation;
import org.kamranzafar.stratum.interceptor.OperationContext;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobStore;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StratumTest {

    @Test
    void discoversInMemoryProviderFromClasspath() {
        try (Stratum stratum = Stratum.builder().register("local", ProviderConfig.of("memory")).build()) {
            BlobStore store = stratum.provider("local").blobStore("bucket");
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(store.provider()).isEqualTo("memory");
            assertThat(store.getBytes(BlobKey.of("k"))).asString().isEqualTo("v");
        }
    }

    @Test
    void namedRegistrationsAreIsolated() {
        try (Stratum stratum = Stratum.builder()
                .register("primary", ProviderConfig.of("memory"))
                .register("archive", ProviderConfig.of("memory"))
                .build()) {
            stratum.provider("primary").blobStore("b").put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(stratum.provider("archive").blobStore("b").exists(BlobKey.of("k"))).isFalse();
            assertThat(stratum.names()).containsExactly("primary", "archive");
        }
    }

    @Test
    void sameBucketReturnsSameClient() {
        try (Stratum stratum = Stratum.builder().register("local", ProviderConfig.of("memory")).build()) {
            CloudContext context = stratum.provider("local");
            assertThat(context.blobStore("b")).isSameAs(context.blobStore("b"));
        }
    }

    @Test
    void missingProviderPluginFailsFastWithHint() {
        assertThatThrownBy(() -> Stratum.builder().register("x", ProviderConfig.of("nope")).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stratum-nope")
                .hasMessageContaining("memory");
    }

    @Test
    void duplicateNamesAreRejected() {
        var builder = Stratum.builder().register("x", ProviderConfig.of("memory"));
        assertThatThrownBy(() -> builder.register("x", ProviderConfig.of("memory")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownNameIsRejected() {
        try (Stratum stratum = Stratum.builder().build()) {
            assertThatThrownBy(() -> stratum.provider("missing")).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void closedContextRefusesNewClients() {
        Stratum stratum = Stratum.builder().register("local", ProviderConfig.of("memory")).build();
        stratum.close();
        assertThatThrownBy(() -> stratum.provider("local").blobStore("b")).isInstanceOf(IllegalStateException.class);
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
        try (Stratum stratum = Stratum.builder()
                .register("local", ProviderConfig.of("memory"))
                .interceptor(recorder)
                .build()) {
            BlobStore store = stratum.provider("local").blobStore("b");
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));
            store.head(BlobKey.of("k"));
            store.delete(BlobKey.of("k"));
        }
        assertThat(seen).containsExactly("storage:put:b/k", "storage:head:b/k", "storage:delete:b/k");
    }
}
