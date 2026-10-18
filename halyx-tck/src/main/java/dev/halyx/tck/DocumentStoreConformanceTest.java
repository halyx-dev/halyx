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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.nosql.AsyncDocumentStore;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.IndexQuery;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.SortCondition;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Transaction;
import dev.halyx.nosql.Version;
import dev.halyx.nosql.WriteOptions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for document stores. Every provider supplies two existing tables: one keyed by
 * partition key only, one with a sort key. Each test works under fresh random partition keys, so the
 * tables can be shared between tests and runs.
 *
 * <p>Both tables declare the index {@value #BY_STATUS} on ({@code status}, {@code placedAt}); the plain
 * table also declares {@value #BY_OWNER} on {@code owner} alone (see {@link #indexes}).
 */
public abstract class DocumentStoreConformanceTest {

    public static final String BY_STATUS = "by-status";
    public static final String BY_OWNER = "by-owner";

    private String prefix;

    /** The indexes a test table declares: {@code sorted} tables only need {@value #BY_STATUS}. */
    public static TableConfig.Builder indexes(TableConfig.Builder table, boolean sorted) {
        table.index(BY_STATUS, "status", "placedAt");
        if (!sorted) {
            table.index(BY_OWNER, "owner");
        }
        return table;
    }

    /** A store over a table keyed by partition key only ({@code sorted = false}) or with a sort key. */
    protected abstract DocumentStore store(boolean sorted);

    /** Tests to skip because the environment does not implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        prefix = "tck-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    private Key key(String partition) {
        return Key.of(prefix + partition);
    }

    private String partition(String name) {
        return prefix + name;
    }

    @Test
    void putAndGetRoundTripsEveryValueType() {
        DocumentStore store = store(false);
        Document document = Document.builder()
                .put("text", "héllo wörld ✓")
                .put("empty", "")
                .put("count", 42)
                .put("big", Long.MAX_VALUE)
                .put("negative", -7L)
                .put("ratio", 0.25)
                .put("flag", true)
                .put("bytes", new byte[] {0, 1, 2, (byte) 0xFF})
                .put("nothing", null)
                .put("tags", List.of("a", "b", 3L))
                .put("nested", Map.of("city", "Leeds", "zip", 12345L, "deeper", Map.of("ok", true)))
                .build();

        Item written = store.put(key("types"), document);
        Item read = store.get(key("types")).orElseThrow();

        assertThat(read.document()).isEqualTo(document);
        assertThat(read.document().getString("text")).hasValue("héllo wörld ✓");
        assertThat(read.document().getLong("big")).hasValue(Long.MAX_VALUE);
        assertThat(read.document().getDouble("ratio")).hasValue(0.25);
        assertThat(read.document().getBinary("bytes")).hasValueSatisfying(b -> assertThat(b).containsExactly(0, 1, 2, 0xFF));
        assertThat(read.document().getDocument("nested").flatMap(n -> n.getDocument("deeper")).flatMap(d -> d.getBoolean("ok")))
                .hasValue(true);
        assertThat(read.document().has("nothing")).isTrue();
        assertThat(read.version()).isEqualTo(written.version());
        assertThat(read.key()).isEqualTo(key("types"));
    }

    @Test
    void decimalsRoundTripAsNumbers() {
        DocumentStore store = store(false);
        store.put(key("decimal"), Document.builder().put("price", new BigDecimal("19.99")).build());

        assertThat(store.get(key("decimal")).orElseThrow().document().getDecimal("price").orElseThrow())
                .isEqualByComparingTo("19.99");
    }

    @Test
    void missingItemIsEmpty() {
        assertThat(store(false).get(key("missing"))).isEmpty();
        assertThat(store(true).get(Key.of(partition("missing"), "x"))).isEmpty();
    }

    @Test
    void putReplacesTheWholeItem() {
        DocumentStore store = store(false);
        store.put(key("replace"), Document.builder().put("a", 1).put("b", 2).build());
        store.put(key("replace"), Document.builder().put("c", 3).build());

        assertThat(store.get(key("replace")).orElseThrow().document().fieldNames()).containsExactly("c");
    }

    @Test
    void versionsGuardConcurrentWrites() {
        DocumentStore store = store(false);
        Item first = store.put(key("version"), Document.builder().put("n", 1).build());
        Item second = store.put(key("version"), Document.builder().put("n", 2).build(), WriteOptions.ifVersion(first.version()));

        assertThat(second.version()).isNotEqualTo(first.version());
        assertThat(store.get(key("version")).orElseThrow().version()).isEqualTo(second.version());
        assertThatThrownBy(() -> store.put(key("version"), Document.builder().put("n", 3).build(),
                WriteOptions.ifVersion(first.version()))).isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> store.put(key("never-written"), Document.empty(), WriteOptions.ifVersion(first.version())))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(store.get(key("version")).orElseThrow().document().getLong("n")).hasValue(2L);
    }

    @Test
    void ifNotExistsCreatesOnlyOnce() {
        DocumentStore store = store(false);
        store.put(key("once"), Document.builder().put("n", 1).build(), WriteOptions.ifNotExists());

        assertThatThrownBy(() -> store.put(key("once"), Document.builder().put("n", 2).build(), WriteOptions.ifNotExists()))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(store.get(key("once")).orElseThrow().document().getLong("n")).hasValue(1L);
    }

    @Test
    void updateSetsRemovesAndIncrements() {
        DocumentStore store = store(false);
        Item original = store.put(key("update"), Document.builder().put("name", "x").put("count", 5).put("drop", true).build());

        Item updated = store.update(key("update"), DocumentUpdate.builder()
                .set("name", "y").set("tags", List.of("t")).remove("drop")
                .increment("count", 3).increment("fresh", 2).build());

        assertThat(updated.document().getString("name")).hasValue("y");
        assertThat(updated.document().getLong("count")).hasValue(8L);
        assertThat(updated.document().getLong("fresh")).hasValue(2L);
        assertThat(updated.document().has("drop")).isFalse();
        assertThat(updated.version()).isNotEqualTo(original.version());
        assertThat(store.get(key("update")).orElseThrow().document()).isEqualTo(updated.document());
    }

    @Test
    void concurrentIncrementsAreNotLost() throws InterruptedException {
        DocumentStore store = store(false);
        store.put(key("counter"), Document.builder().put("hits", 0).build());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int j = 0; j < 5; j++) {
                    store.update(key("counter"), DocumentUpdate.builder().increment("hits", 1).build());
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(store.get(key("counter")).orElseThrow().document().getLong("hits")).hasValue(50L);
    }

    @Test
    void updateNeedsAnExistingItemAndTheRightVersion() {
        DocumentStore store = store(false);
        var change = DocumentUpdate.builder().set("a", 1).build();
        assertThatThrownBy(() -> store.update(key("absent"), change)).isInstanceOf(NotFoundException.class);
        assertThat(store.get(key("absent"))).isEmpty();

        Item item = store.put(key("guarded"), Document.builder().put("a", 0).build());
        store.update(key("guarded"), change, WriteOptions.ifVersion(item.version()));
        assertThatThrownBy(() -> store.update(key("guarded"), DocumentUpdate.builder().set("a", 2).build(),
                WriteOptions.ifVersion(item.version()))).isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void deleteReportsWhetherAnItemWasRemoved() {
        DocumentStore store = store(false);
        Item item = store.put(key("delete"), Document.builder().put("a", 1).build());
        store.put(key("delete"), Document.builder().put("a", 2).build());

        assertThatThrownBy(() -> store.delete(key("delete"), item.version())).isInstanceOf(PreconditionFailedException.class);
        assertThat(store.delete(key("delete"))).isTrue();
        assertThat(store.delete(key("delete"))).isFalse();
        assertThat(store.get(key("delete"))).isEmpty();
    }

    @Test
    void getAllReturnsWhatExistsAcrossBatches() {
        DocumentStore store = store(false);
        Map<Key, Document> items = new LinkedHashMap<>();
        for (int i = 0; i < 130; i++) {
            items.put(key("many-" + i), Document.builder().put("i", i).build());
        }
        store.putAll(items);
        List<Key> wanted = new ArrayList<>(items.keySet());
        wanted.add(key("not-there"));

        Map<Key, Item> found = store.getAll(wanted);

        assertThat(found).hasSize(130).doesNotContainKey(key("not-there"));
        assertThat(found.get(key("many-77")).document().getLong("i")).hasValue(77L);
    }

    @Test
    void queryReturnsAPartitionInSortOrder() {
        DocumentStore store = store(true);
        String p = partition("orders");
        Map<Key, Document> items = new LinkedHashMap<>();
        for (String sort : List.of("2026-03", "2026-01", "2025-12", "2026-02", "2027-01")) {
            items.put(Key.of(p, sort), Document.builder().put("month", sort).build());
        }
        items.put(Key.of(partition("other"), "2026-01"), Document.builder().put("month", "other").build());
        store.putAll(items);

        assertThat(months(store.query(Query.partition(p)))).containsExactly("2025-12", "2026-01", "2026-02", "2026-03", "2027-01");
        assertThat(months(store.query(Query.builder(p).where(SortCondition.beginsWith("2026-")).build())))
                .containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(months(store.query(Query.builder(p).where(SortCondition.between("2026-01", "2026-02")).build())))
                .containsExactly("2026-01", "2026-02");
        assertThat(months(store.query(Query.builder(p).where(SortCondition.greaterThan("2026-02")).build())))
                .containsExactly("2026-03", "2027-01");
        assertThat(months(store.query(Query.builder(p).where(SortCondition.lessThanOrEqual("2026-01")).build())))
                .containsExactly("2025-12", "2026-01");
        assertThat(months(store.query(Query.builder(p).descending().limit(2).build()))).containsExactly("2027-01", "2026-03");
        assertThat(months(store.query(Query.partition(partition("nobody"))))).isEmpty();
    }

    @Test
    void queryPagesThroughLargePartitions() {
        DocumentStore store = store(true);
        String p = partition("paged");
        Map<Key, Document> items = new LinkedHashMap<>();
        for (int i = 0; i < 45; i++) {
            items.put(Key.of(p, String.format("%03d", i)), Document.builder().put("i", i).build());
        }
        store.putAll(items);

        QueryPage first = store.query(Query.builder(p).limit(20).build());
        assertThat(first.items()).hasSize(20);
        assertThat(first.nextPageToken()).isPresent();
        List<String> all = store.queryAll(Query.builder(p).limit(20).build()).map(i -> i.key().sort().orElseThrow()).toList();
        assertThat(all).hasSize(45).isSorted().startsWith("000").endsWith("044");
        assertThat(store.queryAll(Query.builder(p).limit(7).descending().build()).map(i -> i.key().sort().orElseThrow()).toList())
                .hasSize(45).startsWith("044", "043");
    }

    @Test
    void keysWithSpecialCharactersRoundTrip() {
        DocumentStore store = store(true);
        String p = partition("odd/key #1?");
        List<String> sorts = List.of("a/b", "a b", "a%2Fb", "ünïcode", "x?y#z", "back\\slash");
        Map<Key, Document> items = new LinkedHashMap<>();
        sorts.forEach(s -> items.put(Key.of(p, s), Document.builder().put("s", s).build()));
        store.putAll(items);

        for (String s : sorts) {
            assertThat(store.get(Key.of(p, s)).orElseThrow().document().getString("s")).hasValue(s);
        }
        assertThat(store.queryAll(Query.partition(p)).map(i -> i.key().sort().orElseThrow()).toList())
                .containsExactlyInAnyOrderElementsOf(sorts);
    }

    @Test
    void expiredItemsAreNeverReturned() throws InterruptedException {
        DocumentStore store = store(true);
        String p = partition("ttl");
        store.put(Key.of(p, "short"), Document.builder().put("a", 1).build(), WriteOptions.builder().ttl(Duration.ofSeconds(2)).build());
        Item lasting = store.put(Key.of(p, "long"), Document.builder().put("a", 2).build(),
                WriteOptions.builder().ttl(Duration.ofHours(1)).build());

        assertThat(store.get(Key.of(p, "short"))).isPresent();
        assertThat(lasting.expiresAt()).isPresent();
        Thread.sleep(3_000);

        assertThat(store.get(Key.of(p, "short"))).isEmpty();
        assertThat(store.getAll(List.of(Key.of(p, "short"), Key.of(p, "long")))).containsOnlyKeys(Key.of(p, "long"));
        assertThat(store.queryAll(Query.partition(p)).map(i -> i.key().sort().orElseThrow()).toList()).containsExactly("long");
        // An expired item the store hasn't removed yet doesn't block a create.
        store.put(Key.of(p, "short"), Document.builder().put("a", 3).build(), WriteOptions.ifNotExists());
        assertThat(store.get(Key.of(p, "short")).orElseThrow().document().getLong("a")).hasValue(3L);
        assertThat(store.get(Key.of(p, "long")).orElseThrow().expiresAt()).isPresent();
    }

    @Test
    void invalidKeysAndReservedFieldsAreRejected() {
        DocumentStore plain = store(false);
        DocumentStore sorted = store(true);
        String pk = plain.table().partitionKey();

        assertThatThrownBy(() -> plain.put(key("bad"), Document.builder().put(pk, "x").build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> sorted.put(Key.of(partition("x")), Document.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sort key");
        assertThatThrownBy(() -> plain.put(Key.of(partition("x"), "s"), Document.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no sort key");
        assertThatThrownBy(() -> plain.query(Query.partition(partition("x")))).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> Document.builder().put("bad", new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexQueryFindsItemsAcrossPartitionsInSortOrder() {
        DocumentStore store = store(true);
        String paid = prefix + "PAID";
        Map<Key, Document> items = new LinkedHashMap<>();
        items.put(Key.of(partition("acme"), "o1"), order(paid, "2026-10-03"));
        items.put(Key.of(partition("acme"), "o2"), order(prefix + "OPEN", "2026-10-01"));
        items.put(Key.of(partition("globex"), "o1"), order(paid, "2026-10-01"));
        items.put(Key.of(partition("initech"), "o9"), order(paid, "2026-10-02"));
        items.put(Key.of(partition("initech"), "o10"), Document.builder().put("placedAt", "2026-10-04").build()); // no status
        store.putAll(items);

        List<Key> keys = eventually(() -> store.queryIndexAll(IndexQuery.builder(BY_STATUS).equalTo(paid).build())
                .map(Item::key).toList(), 3);

        assertThat(keys).containsExactly(Key.of(partition("globex"), "o1"), Key.of(partition("initech"), "o9"),
                Key.of(partition("acme"), "o1"));
        assertThat(store.queryIndexAll(IndexQuery.builder(BY_STATUS).equalTo(paid).descending().build())
                .map(i -> i.document().getString("placedAt").orElseThrow()).toList())
                .containsExactly("2026-10-03", "2026-10-02", "2026-10-01");
        assertThat(store.queryIndexAll(IndexQuery.builder(BY_STATUS).equalTo(paid)
                        .where(SortCondition.greaterThan("2026-10-01")).build())
                .map(i -> i.document().getString("placedAt").orElseThrow()).toList())
                .containsExactly("2026-10-02", "2026-10-03");
        Item found = store.queryIndexAll(IndexQuery.builder(BY_STATUS).equalTo(paid).build()).findFirst().orElseThrow();
        assertThat(found.document().getString("status")).hasValue(paid);
    }

    @Test
    void indexQueryPagesThroughManyMatches() {
        DocumentStore store = store(true);
        String status = prefix + "BULK";
        Map<Key, Document> items = new LinkedHashMap<>();
        for (int i = 0; i < 23; i++) {
            items.put(Key.of(partition("p" + (i % 4)), "o" + i), order(status, String.format("2026-10-%02d", i + 1)));
        }
        store.putAll(items);

        QueryPage first = eventually(() -> store.queryIndex(IndexQuery.builder(BY_STATUS).equalTo(status).limit(10).build()), 10);
        List<String> all = eventually(() -> store.queryIndexAll(IndexQuery.builder(BY_STATUS).equalTo(status).limit(10).build())
                .map(i -> i.document().getString("placedAt").orElseThrow()).toList(), 23);

        assertThat(first.nextPageToken()).isPresent();
        assertThat(all).hasSize(23).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void indexWithoutSortAttributeFindsEveryMatch() {
        DocumentStore store = store(false);
        String owner = prefix + "ada";
        store.put(key("doc-1"), Document.builder().put("owner", owner).put("title", "a").build());
        store.put(key("doc-2"), Document.builder().put("owner", owner).put("title", "b").build());
        store.put(key("doc-3"), Document.builder().put("owner", prefix + "bob").put("title", "c").build());

        List<String> titles = eventually(() -> store.queryIndexAll(IndexQuery.builder(BY_OWNER).equalTo(owner).build())
                .map(i -> i.document().getString("title").orElseThrow()).sorted().toList(), 2);

        assertThat(titles).containsExactly("a", "b");
        assertThatThrownBy(() -> store.queryIndex(IndexQuery.builder("no-such-index").equalTo("x").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.queryIndex(IndexQuery.builder(BY_OWNER).equalTo("x")
                .where(SortCondition.beginsWith("a")).build())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transactionAppliesEveryWriteTogether() {
        DocumentStore store = store(true);
        String p = partition("tx");
        Item counter = store.put(Key.of(p, "counter"), Document.builder().put("orders", 1).build());
        store.put(Key.of(p, "draft"), Document.builder().put("temp", true).build());

        Map<Key, Version> versions = store.transact(Transaction.builder()
                .put(Key.of(p, "order#2"), Document.builder().put("total", 30).build(), WriteOptions.ifNotExists())
                .put(Key.of(p, "order#2#line#1"), Document.builder().put("sku", "A").build())
                .update(Key.of(p, "counter"), DocumentUpdate.builder().increment("orders", 1).build(),
                        WriteOptions.ifVersion(counter.version()))
                .delete(Key.of(p, "draft"))
                .build());

        assertThat(versions).containsOnlyKeys(Key.of(p, "order#2"), Key.of(p, "order#2#line#1"), Key.of(p, "counter"));
        assertThat(store.get(Key.of(p, "counter")).orElseThrow().document().getLong("orders")).hasValue(2L);
        assertThat(store.get(Key.of(p, "counter")).orElseThrow().version()).isEqualTo(versions.get(Key.of(p, "counter")));
        assertThat(store.get(Key.of(p, "order#2")).orElseThrow().version()).isEqualTo(versions.get(Key.of(p, "order#2")));
        assertThat(store.get(Key.of(p, "draft"))).isEmpty();
    }

    @Test
    void failedConditionRollsBackTheWholeTransaction() {
        DocumentStore store = store(true);
        String p = partition("rollback");
        Item account = store.put(Key.of(p, "account"), Document.builder().put("balance", 100).build());
        store.update(Key.of(p, "account"), DocumentUpdate.builder().increment("balance", -10).build()); // version moves on
        store.put(Key.of(p, "existing"), Document.builder().put("a", 1).build());

        assertThatThrownBy(() -> store.transact(Transaction.builder()
                .put(Key.of(p, "new"), Document.builder().put("a", 1).build())
                .update(Key.of(p, "account"), DocumentUpdate.builder().increment("balance", -50).build(),
                        WriteOptions.ifVersion(account.version()))
                .build())).isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> store.transact(Transaction.builder()
                .put(Key.of(p, "new"), Document.builder().put("a", 1).build())
                .put(Key.of(p, "existing"), Document.builder().put("a", 2).build(), WriteOptions.ifNotExists())
                .build())).isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> store.transact(Transaction.builder()
                .put(Key.of(p, "new"), Document.builder().put("a", 1).build())
                .update(Key.of(p, "missing"), DocumentUpdate.builder().set("a", 1).build())
                .build())).isInstanceOf(PreconditionFailedException.class);

        assertThat(store.get(Key.of(p, "new"))).isEmpty();
        assertThat(store.get(Key.of(p, "account")).orElseThrow().document().getLong("balance")).hasValue(90L);
        assertThat(store.get(Key.of(p, "existing")).orElseThrow().document().getLong("a")).hasValue(1L);
    }

    @Test
    void checksGuardATransactionWithoutWriting() {
        DocumentStore store = store(true);
        String p = partition("check");
        Item rule = store.put(Key.of(p, "rule"), Document.builder().put("limit", 5).build());

        store.transact(Transaction.builder().check(Key.of(p, "rule"), rule.version())
                .put(Key.of(p, "entry"), Document.builder().put("n", 1).build()).build());
        Item changed = store.put(Key.of(p, "rule"), Document.builder().put("limit", 3).build());

        assertThatThrownBy(() -> store.transact(Transaction.builder().check(Key.of(p, "rule"), rule.version())
                .put(Key.of(p, "entry"), Document.builder().put("n", 2).build()).build()))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(store.get(Key.of(p, "entry")).orElseThrow().document().getLong("n")).hasValue(1L);
        assertThat(store.get(Key.of(p, "rule")).orElseThrow().version()).isEqualTo(changed.version());
    }

    @Test
    void transactionsStayWithinOnePartition() {
        assertThatThrownBy(() -> Transaction.builder().put(Key.of("a", "1"), Document.empty())
                .put(Key.of("b", "1"), Document.empty()).build()).hasMessageContaining("one partition");
        assertThatThrownBy(() -> Transaction.builder().put(Key.of("a", "1"), Document.empty())
                .delete(Key.of("a", "1")).build()).hasMessageContaining("twice");
        Transaction.Builder big = Transaction.builder();
        for (int i = 0; i <= Transaction.MAX_OPERATIONS; i++) {
            big.put(Key.of("a", "k" + i), Document.empty());
        }
        assertThatThrownBy(big::build).hasMessageContaining("at most");
    }

    private static Document order(String status, String placedAt) {
        return Document.builder().put("status", status).put("placedAt", placedAt).build();
    }

    /** Index reads may lag writes (DynamoDB GSIs): retry until {@code expected} results show up, briefly. */
    private static <T> T eventually(Supplier<T> read, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        T result = read.get();
        while (size(result) < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            result = read.get();
        }
        return result;
    }

    private static int size(Object result) {
        return result instanceof List<?> list ? list.size() : result instanceof QueryPage page ? page.items().size() : 0;
    }

    private static List<String> months(QueryPage page) {
        return page.items().stream().map(i -> i.document().getString("month").orElseThrow()).toList();
    }

    // --- the asynchronous view -------------------------------------------------------------------
    // Non-blocking where the driver has a path for it (DynamoDB), the blocking call on a virtual thread elsewhere:
    // either way, the same results and the same CloudExceptions as the blocking calls.

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    void asyncCrudMatchesTheBlockingCalls() throws Exception {
        AsyncDocumentStore async = store(false).async();
        Key key = key("async");

        Item put = await(async.put(key, Document.builder().put("n", 1).build()));
        assertThat(await(async.get(key))).hasValueSatisfying(item -> {
            assertThat(item.document().getLong("n")).hasValue(1L);
            assertThat(item.version()).isEqualTo(put.version());
        });
        assertThat(store(false).get(key)).isPresent();

        Item updated = await(async.update(key, DocumentUpdate.builder().increment("n", 2).set("s", "x").build()));
        assertThat(updated.document().getLong("n")).hasValue(3L);
        assertThat(updated.version()).isNotEqualTo(put.version());

        assertThatThrownBy(() -> await(async.put(key, Document.empty(), WriteOptions.ifVersion(put.version()))))
                .isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> await(async.put(key, Document.empty(), WriteOptions.ifNotExists())))
                .isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> await(async.delete(key, put.version()))).isInstanceOf(PreconditionFailedException.class);

        assertThat(await(async.delete(key, updated.version()))).isTrue();
        assertThat(await(async.get(key))).isEmpty();
        assertThat(await(async.delete(key))).isFalse();
    }

    @Test
    void asyncUpdateOfAMissingItemFailsWithNotFound() {
        assertThatThrownBy(() -> await(store(false).async().update(key("async-missing"),
                DocumentUpdate.builder().set("a", 1).build()))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void asyncQueriesMatchTheBlockingOnes() throws Exception {
        DocumentStore store = store(true);
        String p = partition("async-orders");
        String status = prefix + "ASYNC";
        Map<Key, Document> items = new LinkedHashMap<>();
        for (String month : List.of("2026-01", "2026-02", "2026-03")) {
            items.put(Key.of(p, month), order(status, month + "-01"));
        }
        store.putAll(items);

        QueryPage page = await(store.async().query(Query.builder(p).limit(2).build()));
        assertThat(page.items()).extracting(item -> item.key().sort().orElseThrow()).containsExactly("2026-01", "2026-02");
        assertThat(page.nextPageToken()).isPresent();
        assertThat(await(store.async().query(Query.builder(p).limit(2).pageToken(page.nextPageToken().get()).build()))
                .items()).extracting(item -> item.key().sort().orElseThrow()).containsExactly("2026-03");

        List<String> placed = eventually(() -> {
            try {
                return await(store.async().queryIndex(IndexQuery.builder(BY_STATUS).equalTo(status).build())).items()
                        .stream().map(i -> i.document().getString("placedAt").orElseThrow()).toList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 3);
        assertThat(placed).containsExactly("2026-01-01", "2026-02-01", "2026-03-01");
    }
}
