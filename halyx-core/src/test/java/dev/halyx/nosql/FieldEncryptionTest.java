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
package dev.halyx.nosql;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptionTest {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();
    private final AtomicInteger unwraps = new AtomicInteger();
    private volatile long delayMillis;
    private Halyx halyx;
    private CryptoKey key;

    @BeforeEach
    void start() {
        halyx = Halyx.builder()
                .register("test", ProviderConfig.of("memory"))
                .interceptor(new Interceptor() {
                    @Override
                    public <T> T intercept(OperationContext context, Invocation<T> next) {
                        if (!context.service().equals("keys") || !context.operation().equals("unwrap")) {
                            return next.proceed();
                        }
                        unwraps.incrementAndGet();
                        mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(delayMillis);
                            return next.proceed();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    }
                })
                .build();
        KeyStore store = halyx.provider("test").keyStore();
        store.unwrap(InMemoryKeys.class).create("fields", KeyPurpose.ENCRYPTION);
        key = store.key("fields");
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    @Test
    void theCodecKeepsEveryTypeExactly() {
        Document nested = Document.builder().put("deep", List.of(1L, "x", new byte[] {9})).build();
        for (Object value : Arrays.asList(null, "", "héllo ✓", true, false, Long.MIN_VALUE, -0.5, 1e300,
                new BigDecimal("-12345678901234567890.000100"), new byte[] {0, (byte) 0xff}, List.of(), nested)) {
            Object normalized = Document.normalize("v", value);
            Object decoded = FieldCodec.decode(FieldCodec.encode(normalized));
            assertThat(Document.builder().put("v", decoded).build())
                    .isEqualTo(Document.builder().put("v", normalized).build());
            if (normalized != null) {
                assertThat(decoded).isInstanceOf(normalized instanceof List<?> ? List.class : normalized.getClass());
            }
        }
        assertThat(((BigDecimal) FieldCodec.decode(FieldCodec.encode(new BigDecimal("1.10")))).scale()).isEqualTo(2);
    }

    @Test
    void theCodecRefusesWhatItDidntWrite() {
        assertThatThrownBy(() -> FieldCodec.decode(new byte[] {2, 0})).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> FieldCodec.decode(new byte[] {1, 99})).isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> FieldCodec.decode(new byte[] {1, 1, 0, 0, 0, 9, 'a'}))
                .isInstanceOf(DataIntegrityException.class);
        assertThatThrownBy(() -> FieldCodec.decode(new byte[] {1, 0, 0})).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void theAadSeparatesEveryPart() {
        assertThat(FieldCodec.aad("t", Key.of("ab", "c"), "f")).isNotEqualTo(FieldCodec.aad("t", Key.of("a", "bc"), "f"));
        assertThat(FieldCodec.aad("t", Key.of("a"), "f")).isNotEqualTo(FieldCodec.aad("t", Key.of("a", "f"), ""))
                .isNotEqualTo(FieldCodec.aad("u", Key.of("a"), "f"));
    }

    @Test
    void aPageUnwrapsItsDataKeysSixteenAtATime() {
        TableConfig table = TableConfig.builder("people").sortKey("sk").encryptedField("email", key)
                .encryptedField("phone", key).build();
        DocumentStore store = halyx.provider("test").documentStore(table);
        for (int i = 0; i < 40; i++) {
            store.put(Key.of("p", "item#" + String.format("%02d", i)), Document.builder()
                    .put("email", i + "@example.com").put("phone", "0" + i).build());
        }
        delayMillis = 50;
        unwraps.set(0);

        QueryPage page = store.query(Query.partition("p"));

        assertThat(page.items()).hasSize(40);
        assertThat(page.items().get(7).document().getString("email")).hasValue("7@example.com");
        assertThat(unwraps.get()).as("one per document, not per field").isEqualTo(40);
        assertThat(mostInFlight.get()).isGreaterThan(1).isLessThanOrEqualTo(FieldEncryption.PARALLEL_UNWRAPS);
    }

    @Test
    void tablesWithTheSameEncryptedFieldsAreEqual() {
        TableConfig a = TableConfig.builder("t").encryptedField("email", key).build();
        assertThat(a).isEqualTo(TableConfig.builder("t").encryptedField("email", key).build())
                .isNotEqualTo(TableConfig.builder("t").build())
                .isNotEqualTo(TableConfig.builder("t").encryptedField("phone", key).build());
        assertThat(a.encryptedFields()).containsOnlyKeys("email");
        assertThat(a.toString()).contains("encrypted=[email]");
        assertThat(Map.of(a, 1)).containsKey(TableConfig.builder("t").encryptedField("email", key).build());
    }
}
