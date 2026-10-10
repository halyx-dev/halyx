package org.kamranzafar.cirrus.tck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.DocumentUpdate;
import org.kamranzafar.cirrus.nosql.Item;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.Query;
import org.kamranzafar.cirrus.nosql.QueryPage;
import org.kamranzafar.cirrus.nosql.SortCondition;
import org.kamranzafar.cirrus.nosql.WriteOptions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for document stores. Every provider supplies two existing tables: one keyed by
 * partition key only, one with a sort key. Each test works under fresh random partition keys, so the
 * tables can be shared between tests and runs.
 */
public abstract class DocumentStoreConformanceTest {

    private String prefix;

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

    private static List<String> months(QueryPage page) {
        return page.items().stream().map(i -> i.document().getString("month").orElseThrow()).toList();
    }
}
