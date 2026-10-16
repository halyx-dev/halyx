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
package dev.halyx.spring.data.document;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Version;
import dev.halyx.spring.data.document.repository.HalyxRepositoryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.repository.CrudRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repositories over the in-memory provider's document store: CRUD, optimistic locking, every supported query
 * shape, and the startup failures for shapes that would scan.
 */
class DocumentRepositoryTest {

    enum Status { OPEN, SHIPPED }

    enum Tier { FREE, PRO }

    @DocumentTable("orders")
    record Order(@PartitionKey String customerId, @SortKey String orderId, BigDecimal total, Status status,
                 LocalDate placedOn, List<String> lines,
                 @org.springframework.data.annotation.Version Version version) {

        static Order of(String customer, String id, Status status, LocalDate placedOn) {
            return new Order(customer, id, new BigDecimal("10.50"), status, placedOn, List.of("a", "b"), null);
        }
    }

    @DocumentTable("customers")
    record Customer(@PartitionKey UUID id, String name, Tier tier) {
    }

    interface OrderRepository extends DocumentRepository<Order, Key> {
        List<Order> findByCustomerId(String customerId);

        List<Order> findByCustomerIdOrderByOrderIdDesc(String customerId);

        List<Order> findByCustomerIdAndOrderIdBetween(String customerId, String from, String to);

        List<Order> findByCustomerIdAndOrderIdStartingWith(String customerId, String prefix);

        List<Order> findByCustomerIdAndOrderIdGreaterThan(String customerId, String after);

        List<Order> findByCustomerIdAndOrderIdLessThanEqual(String customerId, String upTo);

        Optional<Order> findByCustomerIdAndOrderId(String customerId, String orderId);

        Optional<Order> findFirstByCustomerIdOrderByOrderIdDesc(String customerId);

        Order findOneByCustomerId(String customerId);

        List<Order> findTop2ByCustomerId(String customerId);

        List<Order> findByCustomerId(String customerId, Limit limit);

        Window<Order> findByCustomerId(String customerId, ScrollPosition position, Limit limit);

        Stream<Order> streamByCustomerId(String customerId);

        List<Order> findByStatus(Status status);

        List<Order> findByStatusAndPlacedOnGreaterThanEqualOrderByPlacedOnDesc(Status status, LocalDate from);

        boolean existsByStatus(Status status);
    }

    interface CustomerRepository extends DocumentRepository<Customer, String> {
    }

    private Halyx halyx;
    private DocumentStore orderStore;
    private DocumentStore customerStore;
    private HalyxRepositoryFactory factory;
    private OrderRepository orders;
    private CustomerRepository customers;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        orderStore = halyx.provider("mem").documentStore(TableConfig.builder("orders").sortKey("sk")
                .index("by-status", "status", "placedOn").build());
        customerStore = halyx.provider("mem").documentStore(TableConfig.of("customers"));
        Map<String, DocumentStore> stores = Map.of("orders", orderStore, "customers", customerStore);
        factory = new HalyxRepositoryFactory(type -> stores.get(type.getAnnotation(DocumentTable.class).value()));
        orders = factory.getRepository(OrderRepository.class);
        customers = factory.getRepository(CustomerRepository.class);
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    private List<Order> placeOrders(String customer, int count) {
        List<Order> placed = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            placed.add(orders.save(Order.of(customer, "o%03d".formatted(i), i % 2 == 0 ? Status.SHIPPED : Status.OPEN,
                    LocalDate.of(2026, 1, 1).plusDays(i - 1))));
        }
        return placed;
    }

    /** The order shipped, at the version it was read at: a change, as some stores keep the version otherwise. */
    private static Order shipped(Order order) {
        return new Order(order.customerId(), order.orderId(), order.total(), Status.SHIPPED, order.placedOn(),
                order.lines(), order.version());
    }

    private static List<String> ids(List<Order> found) {
        return found.stream().map(Order::orderId).toList();
    }

    // --- CRUD ---------------------------------------------------------------------------------------------

    @Test
    void saveStoresTheRecordWithTheKeyOutsideTheDocument() {
        Order saved = orders.save(Order.of("c1", "o001", Status.OPEN, LocalDate.of(2026, 1, 1)));

        assertThat(saved.version()).isNotNull();
        var item = orderStore.get(Key.of("c1", "o001")).orElseThrow();
        assertThat(item.document().fieldNames()).containsExactlyInAnyOrder("total", "status", "placedOn", "lines");
        assertThat(item.document().get("status")).isEqualTo("OPEN");
        assertThat(orders.findById(Key.of("c1", "o001"))).contains(saved);
    }

    @Test
    void stringIdsForTablesWithoutASortKey() {
        UUID id = UUID.randomUUID();
        Customer saved = customers.save(new Customer(id, "Ada", Tier.PRO));

        assertThat(customerStore.get(Key.of(id.toString()))).isPresent();
        assertThat(customers.findById(id.toString())).contains(saved);
        assertThat(customers.existsById(id.toString())).isTrue();
        customers.deleteById(id.toString());
        assertThat(customers.existsById(id.toString())).isFalse();
    }

    @Test
    void unversionedEntitiesAreOverwritten() {
        UUID id = UUID.randomUUID();
        customers.save(new Customer(id, "Ada", Tier.FREE));
        customers.save(new Customer(id, "Ada Lovelace", Tier.PRO));

        assertThat(customers.findById(id.toString())).map(Customer::name).contains("Ada Lovelace");
    }

    @Test
    void wrongIdTypesAreRejected() {
        assertThatThrownBy(() -> orders.findById(Key.of("c1"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort key");
        assertThatThrownBy(() -> customers.findById(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void findAllByIdKeepsTheOrderOfTheIdsAndSkipsMissingOnes() {
        placeOrders("c1", 3);

        List<Order> found = orders.findAllById(List.of(Key.of("c1", "o003"), Key.of("c1", "nope"), Key.of("c1", "o001")));

        assertThat(ids(found)).containsExactly("o003", "o001");
    }

    @Test
    void saveAllAndDeleteAll() {
        List<Customer> saved = customers.saveAll(List.of(new Customer(UUID.randomUUID(), "A", Tier.FREE),
                new Customer(UUID.randomUUID(), "B", Tier.PRO)));
        List<String> savedIds = saved.stream().map(c -> c.id().toString()).toList();
        assertThat(customers.findAllById(savedIds)).hasSize(2);

        customers.deleteAll(saved);

        assertThat(customers.findAllById(savedIds)).isEmpty();
    }

    // --- optimistic locking -------------------------------------------------------------------------------

    @Test
    void savingAgainWithTheReadVersionSucceedsAndChangesTheVersion() {
        Order first = orders.save(Order.of("c1", "o001", Status.OPEN, LocalDate.of(2026, 1, 1)));
        Order shipped = orders.save(new Order(first.customerId(), first.orderId(), first.total(), Status.SHIPPED,
                first.placedOn(), first.lines(), first.version()));

        assertThat(shipped.version()).isNotEqualTo(first.version());
        assertThat(orders.findById(Key.of("c1", "o001"))).map(Order::status).contains(Status.SHIPPED);
    }

    @Test
    void savingWithAStaleVersionFails() {
        Order first = orders.save(Order.of("c1", "o001", Status.OPEN, LocalDate.of(2026, 1, 1)));
        orders.save(shipped(first));

        assertThatThrownBy(() -> orders.save(first)).isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("c1/o001");
    }

    @Test
    void savingANewEntityWhoseIdIsTakenFails() {
        orders.save(Order.of("c1", "o001", Status.OPEN, LocalDate.of(2026, 1, 1)));

        assertThatThrownBy(() -> orders.save(Order.of("c1", "o001", Status.OPEN, LocalDate.of(2026, 1, 1))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deletingWithAStaleVersionFailsAndWithTheCurrentOneSucceeds() {
        Order first = orders.save(Order.of("c1", "o001", Status.OPEN, LocalDate.of(2026, 1, 1)));
        Order second = orders.save(shipped(first));

        assertThatThrownBy(() -> orders.delete(first)).isInstanceOf(OptimisticLockingFailureException.class);
        orders.delete(second);
        assertThat(orders.existsById(Key.of("c1", "o001"))).isFalse();
    }

    // --- derived queries ----------------------------------------------------------------------------------

    @Test
    void queriesOnThePartitionKeyComeInSortKeyOrder() {
        placeOrders("c1", 5);
        placeOrders("c2", 2);

        assertThat(ids(orders.findByCustomerId("c1"))).containsExactly("o001", "o002", "o003", "o004", "o005");
        assertThat(ids(orders.findByCustomerIdOrderByOrderIdDesc("c2"))).containsExactly("o002", "o001");
        assertThat(orders.findByCustomerId("nobody")).isEmpty();
    }

    @Test
    void sortKeyConditions() {
        placeOrders("c1", 5);

        assertThat(ids(orders.findByCustomerIdAndOrderIdBetween("c1", "o002", "o004")))
                .containsExactly("o002", "o003", "o004");
        assertThat(ids(orders.findByCustomerIdAndOrderIdStartingWith("c1", "o00"))).hasSize(5);
        assertThat(ids(orders.findByCustomerIdAndOrderIdGreaterThan("c1", "o003"))).containsExactly("o004", "o005");
        assertThat(ids(orders.findByCustomerIdAndOrderIdLessThanEqual("c1", "o002"))).containsExactly("o001", "o002");
        assertThat(orders.findByCustomerIdAndOrderId("c1", "o004")).map(Order::orderId).contains("o004");
        assertThat(orders.findByCustomerIdAndOrderId("c1", "o404")).isEmpty();
    }

    @Test
    void limitsFromTheNameAndFromALimitParameter() {
        placeOrders("c1", 5);

        assertThat(orders.findFirstByCustomerIdOrderByOrderIdDesc("c1")).map(Order::orderId).contains("o005");
        assertThat(ids(orders.findTop2ByCustomerId("c1"))).containsExactly("o001", "o002");
        assertThat(ids(orders.findByCustomerId("c1", Limit.of(3)))).containsExactly("o001", "o002", "o003");
        assertThat(ids(orders.findByCustomerId("c1", Limit.unlimited()))).hasSize(5);
    }

    @Test
    void aSingleResultQueryFindingMoreThanOneFails() {
        placeOrders("c1", 2);

        assertThatThrownBy(() -> orders.findOneByCustomerId("c1"))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
        assertThat(orders.findOneByCustomerId("nobody")).isNull();
    }

    @Test
    void manyPagesAreFollowed() {
        placeOrders("c1", 250);

        assertThat(orders.findByCustomerId("c1")).hasSize(250);
        try (Stream<Order> stream = orders.streamByCustomerId("c1")) {
            assertThat(stream.map(Order::orderId).skip(240)).hasSize(10).first().isEqualTo("o241");
        }
    }

    @Test
    void windowsScrollForwardWithKeysetPositions() {
        placeOrders("c1", 7);

        List<String> seen = new ArrayList<>();
        Window<Order> window = orders.findByCustomerId("c1", ScrollPosition.keyset(), Limit.of(3));
        int windows = 1;
        seen.addAll(ids(window.getContent()));
        while (window.hasNext()) {
            window = orders.findByCustomerId("c1", window.positionAt(window.size() - 1), Limit.of(3));
            seen.addAll(ids(window.getContent()));
            windows++;
        }

        assertThat(seen).containsExactly("o001", "o002", "o003", "o004", "o005", "o006", "o007");
        assertThat(windows).isEqualTo(3);
    }

    @Test
    void offsetAndBackwardPositionsAreRejected() {
        placeOrders("c1", 2);

        assertThatThrownBy(() -> orders.findByCustomerId("c1", ScrollPosition.offset(5), Limit.of(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keyset");
        assertThatThrownBy(() -> orders.findByCustomerId("c1", ScrollPosition.backward(Map.of("pageToken", "x")),
                Limit.of(1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forward");
    }

    @Test
    void queriesOnADeclaredIndex() {
        placeOrders("c1", 4);
        placeOrders("c2", 4);

        assertThat(orders.findByStatus(Status.SHIPPED)).hasSize(4).allMatch(o -> o.status() == Status.SHIPPED);
        assertThat(orders.findByStatusAndPlacedOnGreaterThanEqualOrderByPlacedOnDesc(Status.OPEN,
                LocalDate.of(2026, 1, 3))).extracting(Order::placedOn)
                .containsExactly(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 3));
        assertThat(orders.existsByStatus(Status.SHIPPED)).isTrue();
        orders.deleteAll(orders.findByStatus(Status.SHIPPED));
        assertThat(orders.existsByStatus(Status.SHIPPED)).isFalse();
    }

    /** The innermost exception creating the repository threw. */
    private Throwable startupFailure(Class<?> repository) {
        Throwable failure = null;
        try {
            factory.getRepository(repository);
        } catch (RuntimeException e) {
            failure = e;
        }
        assertThat(failure).as(repository.getSimpleName() + " should fail at startup").isNotNull();
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    // --- startup failures ---------------------------------------------------------------------------------

    interface ScanningRepository extends DocumentRepository<Order, Key> {
        List<Order> findByTotal(BigDecimal total);
    }

    interface CrudOrders extends CrudRepository<Order, Key> {
    }

    interface CountingRepository extends DocumentRepository<Order, Key> {
        long countByCustomerId(String customerId);
    }

    interface PagingRepository extends DocumentRepository<Order, Key> {
        Slice<Order> findByCustomerId(String customerId, Pageable page);
    }

    interface OrRepository extends DocumentRepository<Order, Key> {
        List<Order> findByCustomerIdOrStatus(String customerId, Status status);
    }

    interface FilteringRepository extends DocumentRepository<Order, Key> {
        List<Order> findByCustomerIdAndTotal(String customerId, BigDecimal total);
    }

    interface OrderingRepository extends DocumentRepository<Order, Key> {
        List<Order> findByCustomerIdOrderByTotal(String customerId);
    }

    interface SortParameterRepository extends DocumentRepository<Order, Key> {
        List<Order> findByCustomerId(String customerId, Sort sort);
    }

    interface WindowWithoutPosition extends DocumentRepository<Order, Key> {
        Window<Order> findByCustomerId(String customerId);
    }

    interface CustomerByKeyRepository extends DocumentRepository<Customer, String> {
        List<Customer> findById(UUID id);
    }

    @Test
    void queriesThatWouldScanFailAtStartup() {
        assertThat(startupFailure(ScanningRepository.class))
                .hasMessageContaining("findByTotal").hasMessageContaining("partition key (customerId)")
                .hasMessageContaining("[status]").hasMessageContaining("scan");
        assertThat(startupFailure(FilteringRepository.class))
                .hasMessageContaining("total would need a scan");
        assertThat(startupFailure(OrRepository.class))
                .hasMessageContaining("Or isn't supported");
    }

    @Test
    void findAllAndCountFailAtStartup() {
        assertThatThrownBy(() -> factory.getRepository(CrudOrders.class)).isInstanceOf(RuntimeException.class);
        assertThat(startupFailure(CountingRepository.class))
                .hasMessageContaining("count");
    }

    @Test
    void offsetsAndUnsupportedOrderingFailAtStartup() {
        assertThat(startupFailure(PagingRepository.class))
                .hasMessageContaining("Pageable");
        assertThat(startupFailure(OrderingRepository.class))
                .hasMessageContaining("only be ordered by orderId");
        assertThat(startupFailure(SortParameterRepository.class))
                .hasMessageContaining("Sort parameter");
        assertThat(startupFailure(WindowWithoutPosition.class))
                .hasMessageContaining("ScrollPosition");
    }

    @Test
    void partitionKeyQueriesOnTablesWithoutASortKeyPointToFindById() {
        assertThat(startupFailure(CustomerByKeyRepository.class))
                .hasMessageContaining("use findById");
    }

    // --- entity mistakes ----------------------------------------------------------------------------------

    @DocumentTable("orders")
    record NoKey(String customerId, String orderId) {
    }

    @DocumentTable("orders")
    record NoSortKey(@PartitionKey String customerId) {
    }

    @DocumentTable("customers")
    record NumericKey(@PartitionKey long id) {
    }

    interface NoKeyRepository extends DocumentRepository<NoKey, Key> {
    }

    interface NoSortKeyRepository extends DocumentRepository<NoSortKey, Key> {
    }

    interface NumericKeyRepository extends DocumentRepository<NumericKey, String> {
    }

    @Test
    void entityMistakesFailAtStartup() {
        assertThat(startupFailure(NoKeyRepository.class))
                .hasMessageContaining("@PartitionKey");
        assertThat(startupFailure(NoSortKeyRepository.class))
                .hasMessageContaining("needs a @SortKey component");
        assertThat(startupFailure(NumericKeyRepository.class))
                .hasMessageContaining("String, UUID, LocalDate or an enum");
    }
}
