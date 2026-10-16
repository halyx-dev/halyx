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
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.spring.data.document.ReactiveDocumentRepository;
import dev.halyx.spring.data.document.repository.HalyxReactiveRepositoryFactory;
import dev.halyx.tck.DocumentRepositoryConformanceTest.KitAccount;
import dev.halyx.tck.DocumentRepositoryConformanceTest.KitNote;
import dev.halyx.tck.DocumentRepositoryConformanceTest.KitOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for reactive Spring Data repositories ({@code ReactiveDocumentRepository}) over a
 * provider's document store: {@link DocumentRepositoryConformanceTest}'s cases through {@code Mono} and
 * {@code Flux}, on the same tables and entities.
 */
public abstract class ReactiveDocumentRepositoryConformanceTest {

    public interface ReactiveKitOrders extends ReactiveDocumentRepository<KitOrder, Key> {
        Flux<KitOrder> findByCustomer(String customer);

        Flux<KitOrder> findByCustomerOrderByOrderIdDesc(String customer);

        Flux<KitOrder> findByCustomerAndOrderIdBetween(String customer, String from, String to);

        Flux<KitOrder> findByCustomerAndOrderIdStartingWith(String customer, String prefix);

        Mono<KitOrder> findFirstByCustomerOrderByOrderIdDesc(String customer);

        Flux<KitOrder> findByCustomer(String customer, Limit limit);

        Mono<Window<KitOrder>> findByCustomer(String customer, ScrollPosition position, Limit limit);

        Flux<KitOrder> findByStatusOrderByPlacedAtDesc(String status);

        Flux<KitOrder> findByStatusAndPlacedAtGreaterThanEqual(String status, String from);

        Mono<Boolean> existsByStatus(String status);
    }

    public interface ReactiveKitAccounts extends ReactiveDocumentRepository<KitAccount, String> {
        Flux<KitAccount> findByOwner(String owner);
    }

    public interface ReactiveKitNotes extends ReactiveDocumentRepository<KitNote, String> {
    }

    private String prefix;
    private ReactiveKitOrders orders;
    private ReactiveKitAccounts accounts;
    private ReactiveKitNotes notes;

    /** A store over a table keyed by partition key only ({@code sorted = false}) or with a sort key. */
    protected abstract DocumentStore store(boolean sorted);

    /** Where the repositories' calls run. */
    protected abstract ReactiveHalyx reactive();

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
        HalyxReactiveRepositoryFactory factory = new HalyxReactiveRepositoryFactory(
                type -> type == KitOrder.class ? sorted : plain, reactive());
        orders = factory.getRepository(ReactiveKitOrders.class);
        accounts = factory.getRepository(ReactiveKitAccounts.class);
        notes = factory.getRepository(ReactiveKitNotes.class);
    }

    private String customer(String name) {
        return prefix + name;
    }

    private List<KitOrder> place(String customer, int count, String status) {
        return Flux.range(1, count)
                .concatMap(i -> orders.save(new KitOrder(customer, "o%03d".formatted(i), status,
                        "2026-10-%02d".formatted(i), new BigDecimal("19.99"), List.of("sku-" + i), null)))
                .collectList().block(Duration.ofMinutes(1));
    }

    private static List<String> ids(Flux<KitOrder> found) {
        return found.map(KitOrder::orderId).collectList().block(Duration.ofMinutes(1));
    }

    @Test
    void savedEntitiesReadBackWithTheirVersion() {
        String customer = customer("ada");
        KitOrder saved = orders.save(new KitOrder(customer, "o001", prefix + "OPEN", "2026-10-01",
                new BigDecimal("123.45"), List.of("a", "b"), null)).block();

        assertThat(saved.version()).isNotNull();
        StepVerifier.create(orders.findById(Key.of(customer, "o001")))
                .assertNext(found -> {
                    assertThat(found.total()).isEqualByComparingTo("123.45");
                    assertThat(found.lines()).containsExactly("a", "b");
                    assertThat(found.version()).isEqualTo(saved.version());
                }).verifyComplete();
        StepVerifier.create(orders.existsById(Key.of(customer, "o002"))).expectNext(false).verifyComplete();
        StepVerifier.create(orders.findById(Key.of(customer, "o002"))).verifyComplete();
    }

    @Test
    void uuidKeysAndStringIds() {
        UUID id = UUID.randomUUID();
        KitAccount saved = accounts.save(new KitAccount(id, prefix + "ada", prefix + "ACTIVE", "2026-10-01", 100, null)).block();

        StepVerifier.create(accounts.findById(id.toString())).expectNext(saved).verifyComplete();
        StepVerifier.create(accounts.delete(saved).then(accounts.findById(id.toString()))).verifyComplete();
    }

    @Test
    void staleVersionsAndTakenIdsAreRejected() {
        String customer = customer("bo");
        KitOrder first = place(customer, 1, prefix + "OPEN").getFirst();
        KitOrder second = orders.save(new KitOrder(customer, "o001", prefix + "SHIPPED", first.placedAt(),
                first.total(), first.lines(), first.version())).block();

        StepVerifier.create(orders.save(first)).verifyError(OptimisticLockingFailureException.class);
        StepVerifier.create(orders.save(new KitOrder(customer, "o001", "x", "y", BigDecimal.ONE, List.of(), null)))
                .verifyError(DuplicateKeyException.class);
        StepVerifier.create(orders.delete(first)).verifyError(OptimisticLockingFailureException.class);
        StepVerifier.create(orders.delete(second).then(orders.existsById(Key.of(customer, "o001"))))
                .expectNext(false).verifyComplete();
    }

    @Test
    void unversionedEntitiesAreSavedInBatchesFromIterablesAndPublishers() {
        List<String> ids = List.of(prefix + "n1", prefix + "n2", prefix + "n3");
        StepVerifier.create(notes.saveAll(ids.stream().map(id -> new KitNote(id, "first")).toList()))
                .expectNextCount(3).verifyComplete();
        StepVerifier.create(notes.saveAll(Flux.just(new KitNote(ids.get(1), "second"))))
                .expectNextCount(1).verifyComplete();

        StepVerifier.create(notes.findAllById(List.of(ids.get(2), prefix + "missing", ids.get(1), ids.get(0)))
                        .map(KitNote::text))
                .expectNext("first", "second", "first").verifyComplete();
        StepVerifier.create(notes.deleteAllById(ids).thenMany(notes.findAllById(ids))).verifyComplete();
    }

    @Test
    void partitionQueriesInSortKeyOrderEitherWay() {
        String customer = customer("cy");
        place(customer, 5, prefix + "OPEN");
        place(customer("other"), 2, prefix + "OPEN");

        assertThat(ids(orders.findByCustomer(customer))).containsExactly("o001", "o002", "o003", "o004", "o005");
        assertThat(ids(orders.findByCustomerOrderByOrderIdDesc(customer))).containsExactly("o005", "o004", "o003",
                "o002", "o001");
        StepVerifier.create(orders.findByCustomer(customer("nobody"))).verifyComplete();
    }

    @Test
    void sortKeyConditions() {
        String customer = customer("di");
        place(customer, 5, prefix + "OPEN");

        assertThat(ids(orders.findByCustomerAndOrderIdBetween(customer, "o002", "o004")))
                .containsExactly("o002", "o003", "o004");
        assertThat(ids(orders.findByCustomerAndOrderIdStartingWith(customer, "o00"))).hasSize(5);
        StepVerifier.create(orders.findFirstByCustomerOrderByOrderIdDesc(customer).map(KitOrder::orderId))
                .expectNext("o005").verifyComplete();
        assertThat(ids(orders.findByCustomer(customer, Limit.of(2)))).containsExactly("o001", "o002");
    }

    @Test
    void windowsScrollThroughAPartition() {
        String customer = customer("ed");
        place(customer, 5, prefix + "OPEN");

        List<String> seen = new ArrayList<>();
        Window<KitOrder> window = orders.findByCustomer(customer, ScrollPosition.keyset(), Limit.of(2)).block();
        seen.addAll(window.getContent().stream().map(KitOrder::orderId).toList());
        while (window.hasNext()) {
            window = orders.findByCustomer(customer, window.positionAt(window.size() - 1), Limit.of(2)).block();
            seen.addAll(window.getContent().stream().map(KitOrder::orderId).toList());
        }

        assertThat(seen).containsExactly("o001", "o002", "o003", "o004", "o005");
    }

    @Test
    void fluxQueriesStreamAndCanStopEarly() {
        String customer = customer("fi");
        place(customer, 3, prefix + "OPEN");

        StepVerifier.create(orders.findByCustomer(customer).map(KitOrder::orderId), 1)
                .expectNext("o001")
                .thenRequest(1)
                .expectNext("o002")
                .thenCancel()
                .verify(Duration.ofMinutes(1));
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
        StepVerifier.create(orders.existsByStatus(shipped)).expectNext(true).verifyComplete();
        StepVerifier.create(orders.existsByStatus(prefix + "NONE")).expectNext(false).verifyComplete();
    }

    @Test
    void indexWithoutASortAttribute() {
        String owner = prefix + "kai";
        Flux.just(new KitAccount(UUID.randomUUID(), owner, prefix + "ACTIVE", "2026-10-01", 5, null),
                        new KitAccount(UUID.randomUUID(), owner, prefix + "ACTIVE", "2026-10-02", 7, null),
                        new KitAccount(UUID.randomUUID(), prefix + "lee", prefix + "ACTIVE", "2026-10-03", 9, null))
                .concatMap(accounts::save).blockLast(Duration.ofMinutes(1));

        assertThat(eventually(() -> accounts.findByOwner(owner), 2)).extracting(KitAccount::balance)
                .containsExactlyInAnyOrder(5L, 7L);
    }

    /** Index reads may lag writes (DynamoDB GSIs): retry until {@code expected} results show up, briefly. */
    private static <T> List<T> eventually(Supplier<Flux<T>> read, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<T> result = read.get().collectList().block(Duration.ofMinutes(1));
        while (result.size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            result = read.get().collectList().block(Duration.ofMinutes(1));
        }
        return result;
    }
}
