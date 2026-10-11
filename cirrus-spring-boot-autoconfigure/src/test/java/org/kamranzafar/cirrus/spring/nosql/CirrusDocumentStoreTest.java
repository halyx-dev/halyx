package org.kamranzafar.cirrus.spring.nosql;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.IndexQuery;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.Query;
import org.kamranzafar.cirrus.nosql.RecordMapper;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CirrusDocumentStoreTest {

    record Order(String customer, long total) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CirrusAutoConfiguration.class))
            .withPropertyValues("cirrus.providers.local.type=memory",
                    "cirrus.document-stores.orders.table=orders-table",
                    "cirrus.document-stores.orders.sort-key=sk",
                    "cirrus.document-stores.orders.indexes.by-customer.partition-attribute=customer",
                    "cirrus.document-stores.profiles.partition-key=id");

    @Test
    void registersDocumentStoreBeans() {
        runner.run(context -> {
            DocumentStore orders = context.getBean("orders", DocumentStore.class);
            DocumentStore profiles = context.getBean("profiles", DocumentStore.class);
            RecordMapper<Order> mapper = RecordMapper.of(Order.class);

            orders.put(Key.of("acme", "2026-10-11"), mapper.toDocument(new Order("acme", 42)));
            profiles.put(Key.of("u1"), Document.builder().put("name", "Ada").build());

            assertThat(orders.table().name()).isEqualTo("orders-table");
            assertThat(orders.table().sortKey()).hasValue("sk");
            assertThat(profiles.table().partitionKey()).isEqualTo("id");
            assertThat(orders.queryAll(Query.partition("acme")).map(i -> mapper.fromDocument(i.document())).toList())
                    .containsExactly(new Order("acme", 42));
            assertThat(profiles.get(Key.of("u1")).orElseThrow().document().getString("name")).hasValue("Ada");
            assertThat(orders.queryIndexAll(IndexQuery.builder("by-customer").equalTo("acme").build()).count()).isEqualTo(1);
        });
    }
}
