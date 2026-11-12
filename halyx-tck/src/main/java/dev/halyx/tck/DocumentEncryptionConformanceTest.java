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

import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.nosql.AsyncDocumentStore;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Encryption-only cases for document stores ({@code TableConfig.Builder.encryptedField}); the whole
 * {@link DocumentStoreConformanceTest} also runs through encrypted fields in each provider's encrypted subclass.
 *
 * <p>A provider supplies its two kit tables and a key store holding the encryption keys {@code fields},
 * {@code tenant-a} and {@code tenant-b}, without a data-key cache (the tests count key service calls). Each test
 * opens an encrypted store and a plain one over the same table: the plain one shows what the database holds.
 */
public abstract class DocumentEncryptionConformanceTest {

    private static final byte[] HLXS = "HLXS".getBytes(StandardCharsets.US_ASCII);

    private String prefix;

    /** The kit table keyed by partition key only ({@code sorted = false}) or with a sort key, with the kit's indexes. */
    protected abstract TableConfig.Builder table(boolean sorted);

    /** A store over {@code table}. */
    protected abstract DocumentStore store(TableConfig table);

    /** The key store with the keys {@code fields}, {@code tenant-a} and {@code tenant-b}, and no data-key cache. */
    protected abstract KeyStore keyStore();

    @BeforeEach
    void newPrefix() {
        prefix = "enc-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    private CryptoKey key() {
        return keyStore().key("fields");
    }

    private DocumentStore encrypted(boolean sorted) {
        return store(table(sorted).encryptedField("email", key()).encryptedField("salary", key())
                .encryptedField("profile", key()).build());
    }

    private DocumentStore plain(boolean sorted) {
        return store(table(sorted).build());
    }

    private Key key(String name) {
        return Key.of(prefix + name);
    }

    private static Document person(String email) {
        return Document.builder()
                .put("email", email)
                .put("salary", new BigDecimal("1234.50"))
                .put("profile", Map.of("city", "Leeds", "tags", List.of("a", 1L), "photo", new byte[] {1, 2, 3}))
                .put("name", "Ana")
                .build();
    }

    private long wraps() {
        return keyStore().statistics().wraps();
    }

    private long unwraps() {
        return keyStore().statistics().unwraps();
    }

    private static void assertSealed(Object stored, String plaintext) {
        assertThat(stored).isInstanceOf(byte[].class);
        byte[] bytes = (byte[]) stored;
        assertThat(Arrays.copyOf(bytes, 4)).as("an HLXS envelope").isEqualTo(HLXS);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain(plaintext);
    }

    @Test
    void fieldsAreSealedAtRestAndReadBackAsWritten() {
        DocumentStore store = encrypted(false);
        Document written = person("ana@example.com");

        store.put(key("ana"), written);

        assertThat(store.get(key("ana")).orElseThrow().document()).isEqualTo(written);
        Document raw = plain(false).get(key("ana")).orElseThrow().document();
        assertSealed(raw.get("email"), "ana@example.com");
        assertSealed(raw.get("salary"), "1234.5");
        assertSealed(raw.get("profile"), "Leeds");
        assertThat(raw.getString("name")).as("other fields stay in the clear").hasValue("Ana");
        assertThat(store.get(key("ana")).orElseThrow().document().getDecimal("salary"))
                .as("decimals stay exact on every store").hasValue(new BigDecimal("1234.50"));
    }

    @Test
    void nullsAndEmptyValuesRoundTrip() {
        DocumentStore store = encrypted(false);
        Document written = Document.builder().put("email", "").put("salary", null)
                .put("profile", List.of()).build();

        store.put(key("empty"), written);

        assertThat(store.get(key("empty")).orElseThrow().document()).isEqualTo(written);
    }

    @Test
    void aDocumentWriteWrapsOnceAndAReadUnwrapsOnce() {
        DocumentStore store = encrypted(false);

        long wraps = wraps();
        store.put(key("calls"), person("calls@example.com"));
        assertThat(wraps() - wraps).as("one data key for the document's three encrypted fields").isEqualTo(1);

        long unwraps = unwraps();
        store.get(key("calls"));
        assertThat(unwraps() - unwraps).as("one unwrap for the document's three encrypted fields").isEqualTo(1);

        SealedData email = SealedData.parse(plain(false).get(key("calls")).orElseThrow().document()
                .getBinary("email").orElseThrow());
        SealedData salary = SealedData.parse(plain(false).get(key("calls")).orElseThrow().document()
                .getBinary("salary").orElseThrow());
        assertThat(email.keys()).as("the fields share a data key").isEqualTo(salary.keys());
        assertThat(email.toBytes()).as("each field is sealed on its own").isNotEqualTo(salary.toBytes());
    }

    @Test
    void aQueryPageUnwrapsOncePerItemAndAsyncDoesTheSame() throws Exception {
        DocumentStore store = encrypted(true);
        String partition = prefix + "page";
        for (int i = 0; i < 5; i++) {
            store.put(Key.of(partition, "item#" + i), person("p" + i + "@example.com"));
        }

        long unwraps = unwraps();
        QueryPage page = store.query(Query.partition(partition));
        assertThat(unwraps() - unwraps).isEqualTo(5);
        assertThat(page.items()).extracting(item -> item.document().getString("email").orElseThrow())
                .containsExactly("p0@example.com", "p1@example.com", "p2@example.com", "p3@example.com",
                        "p4@example.com");

        unwraps = unwraps();
        QueryPage async = await(store.async().query(Query.partition(partition)));
        assertThat(unwraps() - unwraps).isEqualTo(5);
        assertThat(async.items()).extracting(Item::document).isEqualTo(page.items().stream().map(Item::document).toList());
    }

    @Test
    void sealedValuesAreBoundToTheirItemAndField() {
        DocumentStore store = encrypted(false);
        DocumentStore plain = plain(false);
        store.put(key("a"), person("a@example.com"));
        store.put(key("b"), person("b@example.com"));
        Document a = plain.get(key("a")).orElseThrow().document();

        plain.put(key("b"), plain.get(key("b")).orElseThrow().document().toBuilder()
                .put("email", a.get("email")).build());
        assertThatThrownBy(() -> store.get(key("b"))).as("moved to another item")
                .isInstanceOf(DataIntegrityException.class);

        plain.put(key("a"), a.toBuilder().put("salary", a.get("email")).build());
        assertThatThrownBy(() -> store.get(key("a"))).as("moved to another field")
                .isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void aChangedValueIsRefused() {
        DocumentStore store = encrypted(false);
        DocumentStore plain = plain(false);
        store.put(key("tampered"), person("t@example.com"));
        Document raw = plain.get(key("tampered")).orElseThrow().document();
        byte[] email = raw.getBinary("email").orElseThrow();
        email[email.length - 1] ^= 1;

        plain.put(key("tampered"), raw.toBuilder().put("email", email).build());

        assertThatThrownBy(() -> store.get(key("tampered"))).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void aValueInTheClearInAnEncryptedFieldIsRefused() {
        plain(false).put(key("clear"), Document.builder().put("email", "clear@example.com").build());

        assertThatThrownBy(() -> encrypted(false).get(key("clear")))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("email")
                .hasMessageNotContaining("clear@example.com");
    }

    @Test
    void itemsWrittenBeforeTheFieldWasEncryptedStillRead() {
        DocumentStore store = encrypted(false);
        plain(false).put(key("old"), Document.builder().put("name", "Old").build());

        assertThat(store.get(key("old")).orElseThrow().document().getString("name")).hasValue("Old");

        Item updated = store.update(key("old"), DocumentUpdate.builder().set("email", "old@example.com").build());
        assertThat(updated.document().getString("email")).hasValue("old@example.com");
        assertSealed(plain(false).get(key("old")).orElseThrow().document().get("email"), "old@example.com");
    }

    @Test
    void updatesSealWhatTheySetAndReturnPlaintext() throws Exception {
        DocumentStore store = encrypted(false);
        store.put(key("up"), person("before@example.com"));

        long wraps = wraps();
        Item updated = store.update(key("up"), DocumentUpdate.builder().set("email", "after@example.com")
                .set("salary", 99L).remove("profile").set("name", "Bea").build());
        assertThat(wraps() - wraps).isEqualTo(1);
        assertThat(updated.document().getString("email")).hasValue("after@example.com");
        assertThat(updated.document().getLong("salary")).hasValue(99L);
        assertThat(updated.document().has("profile")).isFalse();
        assertSealed(plain(false).get(key("up")).orElseThrow().document().get("email"), "after@example.com");

        AsyncDocumentStore async = store.async();
        Item again = await(async.update(key("up"), DocumentUpdate.builder().set("email", "async@example.com").build()));
        assertThat(again.document().getString("email")).hasValue("async@example.com");
        assertThat(await(async.get(key("up"))).orElseThrow().document().getString("email"))
                .hasValue("async@example.com");
    }

    @Test
    void encryptedFieldsCantBeIncrementedOrKeysOrIndexed() {
        DocumentStore store = encrypted(false);
        store.put(key("n"), person("n@example.com"));

        assertThatThrownBy(() -> store.update(key("n"), DocumentUpdate.builder().increment("salary", 1).build()))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> await(store.async().update(key("n"),
                DocumentUpdate.builder().increment("salary", 1).build())))
                .isInstanceOf(InvalidArgumentException.class);
        TableConfig sorted = table(true).build();
        assertThatThrownBy(() -> table(false).encryptedField(sorted.partitionKey(), key()).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table(true).encryptedField(sorted.sortKey().orElseThrow(), key()).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> table(false).encryptedField("status", key()).build())
                .as("an index attribute").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void perItemKeysSealEachItemWithItsOwnKey() {
        DocumentStore store = store(table(false).encryptedField("email", keyStore(),
                key -> key.partition().endsWith("-a") ? "tenant-a" : "tenant-b").build());

        store.put(key("tenant-a"), Document.builder().put("email", "a@example.com").build());
        store.put(key("tenant-b"), Document.builder().put("email", "b@example.com").build());

        assertThat(store.get(key("tenant-a")).orElseThrow().document().getString("email")).hasValue("a@example.com");
        assertThat(store.get(key("tenant-b")).orElseThrow().document().getString("email")).hasValue("b@example.com");
        DocumentStore plain = plain(false);
        assertThat(sealedKey(plain, key("tenant-a"))).isEqualTo(keyId("tenant-a"));
        assertThat(sealedKey(plain, key("tenant-b"))).isEqualTo(keyId("tenant-b"));
    }

    /** The key ID {@code name} writes into sealed data (an ARN, a vault URL or a full resource name). */
    private String keyId(String name) {
        try (DataKey dataKey = keyStore().key(name).generateDataKey()) {
            return dataKey.wrapped().keyId();
        }
    }

    private static String sealedKey(DocumentStore plain, Key key) {
        return SealedData.parse(plain.get(key).orElseThrow().document().getBinary("email").orElseThrow())
                .keys().getFirst().keyId();
    }

    @Test
    void anyKeyOfTheStoreOpensAField() {
        encrypted(false).put(key("moved"), person("moved@example.com"));

        DocumentStore newKey = store(table(false).encryptedField("email", keyStore().key("tenant-a"))
                .encryptedField("salary", keyStore().key("tenant-a"))
                .encryptedField("profile", keyStore().key("tenant-a")).build());

        assertThat(newKey.get(key("moved")).orElseThrow().document()).isEqualTo(person("moved@example.com"));
    }

    @Test
    void batchesAndTransactionsSealEveryDocument() {
        DocumentStore store = encrypted(true);
        String partition = prefix + "batch";
        Map<Key, Document> items = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            items.put(Key.of(partition, "b#" + i), person("b" + i + "@example.com"));
        }

        long wraps = wraps();
        store.putAll(items);
        assertThat(wraps() - wraps).as("one data key per document").isEqualTo(3);
        long unwraps = unwraps();
        Map<Key, Item> read = store.getAll(items.keySet());
        assertThat(unwraps() - unwraps).isEqualTo(3);
        items.forEach((key, document) -> assertThat(read.get(key).document()).isEqualTo(document));

        store.transact(Transaction.builder()
                .put(Key.of(partition, "t#1"), person("t1@example.com"))
                .update(Key.of(partition, "b#0"), DocumentUpdate.builder().set("email", "t0@example.com").build())
                .build());
        assertThat(store.get(Key.of(partition, "t#1")).orElseThrow().document()).isEqualTo(person("t1@example.com"));
        assertThat(store.get(Key.of(partition, "b#0")).orElseThrow().document().getString("email"))
                .hasValue("t0@example.com");
        DocumentStore plain = plain(true);
        assertSealed(plain.get(Key.of(partition, "t#1")).orElseThrow().document().get("email"), "t1@example.com");
        assertSealed(plain.get(Key.of(partition, "b#0")).orElseThrow().document().get("email"), "t0@example.com");
    }

    @Test
    void aStoreWithoutEncryptedFieldsMakesNoKeyServiceCalls() {
        DocumentStore plain = plain(false);
        long wraps = wraps();
        long unwraps = unwraps();

        plain.put(key("none"), Document.builder().put("email", "none@example.com").build());
        plain.get(key("none"));
        plain.update(key("none"), DocumentUpdate.builder().set("email", "x@example.com").build());

        assertThat(wraps()).isEqualTo(wraps);
        assertThat(unwraps()).isEqualTo(unwraps);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
