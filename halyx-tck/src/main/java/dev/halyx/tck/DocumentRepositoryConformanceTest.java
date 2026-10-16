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

import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Version;
import dev.halyx.spring.data.document.DocumentRepository;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.SortKey;
import dev.halyx.spring.data.document.repository.HalyxRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for Spring Data repositories ({@code halyx-spring-data-document}) over a provider's document store, on
 * the same two tables as {@link DocumentStoreConformanceTest}: orders in the table with a sort key, accounts and
 * notes in the plain one, found through the indexes both declare. Every key and index value starts with a prefix
 * of the test's own, so tests never see each other's items.
 */
public abstract class DocumentRepositoryConformanceTest {

    /** An order, versioned, in the table with a sort key; {@code status} and {@code placedAt} are indexed. */
    public record KitOrder(@PartitionKey String customer, @SortKey String orderId, String status, String placedAt,
                           BigDecimal total, List<String> lines,
                           @org.springframework.data.annotation.Version Version version) {
    }

    /** An account, versioned, in the plain table; {@code owner} and {@code status} are indexed. */
    public record KitAccount(@PartitionKey UUID id, String owner, String status, String placedAt, long balance,
                             @org.springframework.data.annotation.Version Version version) {
    }

    /** An unversioned note in the plain table. */
    public record KitNote(@PartitionKey String id, String text) {
    }

    public interface KitOrders extends DocumentRepository<KitOrder, Key> {
        List<KitOrder> findByCustomer(String customer);

        List<KitOrder> findByCustomerOrderByOrderIdDesc(String customer);

        List<KitOrder> findByCustomerAndOrderIdBetween(String customer, String from, String to);

        List<KitOrder> findByCustomerAndOrderIdStartingWith(String customer, String prefix);

        Optional<KitOrder> findFirstByCustomerOrderByOrderIdDesc(String customer);

        List<KitOrder> findByCustomer(String customer, Limit limit);

        Window<KitOrder> findByCustomer(String customer, ScrollPosition position, Limit limit);

        Stream<KitOrder> streamByCustomer(String customer);

        List<KitOrder> findByStatusOrderByPlacedAtDesc(String status);

        List<KitOrder> findByStatusAndPlacedAtGreaterThanEqual(String status, String from);

        boolean existsByStatus(String status);
    }

    public interface KitAccounts extends DocumentRepository<KitAccount, String> {
        List<KitAccount> findByOwner(String owner);
    }

    public interface KitNotes extends DocumentRepository<KitNote, String> {
    }

    private String prefix;
    private KitOrders orders;
    private KitAccounts accounts;
    private KitNotes notes;

    /** A store over a table keyed by partition key only ({@code sorted = false}) or with a sort key. */
    protected abstract DocumentStore store(boolean sorted);

    /** Tests to skip because the environment does not implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void repositories(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        prefix = "tck-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        DocumentStore sorted = store(true);
        DocumentStore plain = store(false);
        HalyxRepositoryFactory factory = new HalyxRepositoryFactory(type -> type == KitOrder.class ? sorted : plain);
        orders = factory.getRepository(KitOrders.class);
        accounts = factory.getRepository(KitAccounts.class);
        notes = factory.getRepository(KitNotes.class);
    }

    private String customer(String name) {
        return prefix + name;
    }

    private List<KitOrder> place(String customer, int count, String status) {
        List<KitOrder> placed = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            placed.add(orders.save(new KitOrder(customer, "o%03d".formatted(i), status,
                    "2026-10-%02d".formatted(i), new BigDecimal("19.99"), List.of("sku-" + i), null)));
        }
        return placed;
    }

    private static List<String> ids(List<KitOrder> found) {
        return found.stream().map(KitOrder::orderId).toList();
    }

    @Test
    void savedEntitiesReadBackWithTheirVersion() {
        String customer = customer("ada");
        KitOrder saved = orders.save(new KitOrder(customer, "o001", prefix + "OPEN", "2026-10-01",
                new BigDecimal("123.45"), List.of("a", "b"), null));

        assertThat(saved.version()).isNotNull();
        assertThat(orders.findById(Key.of(customer, "o001"))).hasValueSatisfying(found -> {
            assertThat(found.total()).isEqualByComparingTo("123.45");
            assertThat(found.lines()).containsExactly("a", "b");
            assertThat(found.version()).isEqualTo(saved.version());
        });
        assertThat(orders.existsById(Key.of(customer, "o002"))).isFalse();
    }

    @Test
    void uuidKeysAndStringIds() {
        UUID id = UUID.randomUUID();
        KitAccount saved = accounts.save(new KitAccount(id, prefix + "ada", prefix + "ACTIVE", "2026-10-01", 100, null));

        assertThat(accounts.findById(id.toString())).contains(saved);
        accounts.delete(saved);
        assertThat(accounts.findById(id.toString())).isEmpty();
    }

    @Test
    void staleVersionsAndTakenIdsAreRejected() {
        String customer = customer("bo");
        KitOrder first = place(customer, 1, prefix + "OPEN").getFirst();
        // A change, since Firestore keeps the version of a write that changes nothing.
        KitOrder second = orders.save(new KitOrder(customer, "o001", prefix + "SHIPPED", first.placedAt(),
                first.total(), first.lines(), first.version()));

        assertThatThrownBy(() -> orders.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> orders.save(new KitOrder(customer, "o001", "x", "y", BigDecimal.ONE, List.of(), null)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> orders.delete(first)).isInstanceOf(OptimisticLockingFailureException.class);
        orders.delete(second);
        assertThat(orders.existsById(Key.of(customer, "o001"))).isFalse();
    }

    @Test
    void unversionedEntitiesAreSavedInBatchesAndOverwritten() {
        List<String> ids = List.of(prefix + "n1", prefix + "n2", prefix + "n3");
        notes.saveAll(ids.stream().map(id -> new KitNote(id, "first")).toList());
        notes.save(new KitNote(ids.get(1), "second"));

        assertThat(notes.findAllById(List.of(ids.get(2), prefix + "missing", ids.get(1), ids.get(0))))
                .extracting(KitNote::text).containsExactly("first", "second", "first");
        notes.deleteAllById(ids);
        assertThat(notes.findAllById(ids)).isEmpty();
    }

    @Test
    void partitionQueriesInSortKeyOrderEitherWay() {
        String customer = customer("cy");
        place(customer, 5, prefix + "OPEN");
        place(customer("other"), 2, prefix + "OPEN");

        assertThat(ids(orders.findByCustomer(customer))).containsExactly("o001", "o002", "o003", "o004", "o005");
        assertThat(ids(orders.findByCustomerOrderByOrderIdDesc(customer))).containsExactly("o005", "o004", "o003",
                "o002", "o001");
        assertThat(orders.findByCustomer(customer("nobody"))).isEmpty();
    }

    @Test
    void sortKeyConditions() {
        String customer = customer("di");
        place(customer, 5, prefix + "OPEN");

        assertThat(ids(orders.findByCustomerAndOrderIdBetween(customer, "o002", "o004")))
                .containsExactly("o002", "o003", "o004");
        assertThat(ids(orders.findByCustomerAndOrderIdStartingWith(customer, "o00"))).hasSize(5);
        assertThat(orders.findFirstByCustomerOrderByOrderIdDesc(customer)).map(KitOrder::orderId).contains("o005");
        assertThat(ids(orders.findByCustomer(customer, Limit.of(2)))).containsExactly("o001", "o002");
    }

    @Test
    void windowsScrollThroughAPartition() {
        String customer = customer("ed");
        place(customer, 5, prefix + "OPEN");

        List<String> seen = new ArrayList<>();
        Window<KitOrder> window = orders.findByCustomer(customer, ScrollPosition.keyset(), Limit.of(2));
        seen.addAll(ids(window.getContent()));
        while (window.hasNext()) {
            window = orders.findByCustomer(customer, window.positionAt(window.size() - 1), Limit.of(2));
            seen.addAll(ids(window.getContent()));
        }

        assertThat(seen).containsExactly("o001", "o002", "o003", "o004", "o005");
    }

    @Test
    void streamsReadLazily() {
        String customer = customer("fi");
        place(customer, 3, prefix + "OPEN");

        try (Stream<KitOrder> stream = orders.streamByCustomer(customer)) {
            assertThat(stream.map(KitOrder::orderId)).containsExactly("o001", "o002", "o003");
        }
    }

    @Test
    void indexQueriesAcrossPartitions() {
        String shipped = prefix + "SHIPPED";
        place(customer("gu"), 2, shipped);
        place(customer("ha"), 2, shipped);
        place(customer("io"), 1, prefix + "OPEN");

        List<KitOrder> found = eventually(() -> orders.findByStatusOrderByPlacedAtDesc(shipped), 4);
        assertThat(found).extracting(KitOrder::placedAt).containsExactly("2026-10-02", "2026-10-02", "2026-10-01",
                "2026-10-01");
        assertThat(eventually(() -> orders.findByStatusAndPlacedAtGreaterThanEqual(shipped, "2026-10-02"), 2))
                .hasSize(2).allMatch(o -> o.placedAt().equals("2026-10-02"));
        assertThat(orders.existsByStatus(shipped)).isTrue();
        assertThat(orders.existsByStatus(prefix + "NONE")).isFalse();
    }

    @Test
    void indexWithoutASortAttribute() {
        String owner = prefix + "kai";
        accounts.save(new KitAccount(UUID.randomUUID(), owner, prefix + "ACTIVE", "2026-10-01", 5, null));
        accounts.save(new KitAccount(UUID.randomUUID(), owner, prefix + "ACTIVE", "2026-10-02", 7, null));
        accounts.save(new KitAccount(UUID.randomUUID(), prefix + "lee", prefix + "ACTIVE", "2026-10-03", 9, null));

        assertThat(eventually(() -> accounts.findByOwner(owner), 2)).extracting(KitAccount::balance)
                .containsExactlyInAnyOrder(5L, 7L);
    }

    /** Index reads may lag writes (DynamoDB GSIs): retry until {@code expected} results show up, briefly. */
    private static <T> List<T> eventually(Supplier<List<T>> read, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<T> result = read.get();
        while (result.size() < expected && System.nanoTime() < deadline) {
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
}
