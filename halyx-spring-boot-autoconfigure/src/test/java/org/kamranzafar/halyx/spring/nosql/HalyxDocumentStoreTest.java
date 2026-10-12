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
package org.kamranzafar.halyx.spring.nosql;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.nosql.Document;
import org.kamranzafar.halyx.nosql.DocumentStore;
import org.kamranzafar.halyx.nosql.IndexQuery;
import org.kamranzafar.halyx.nosql.Key;
import org.kamranzafar.halyx.nosql.Query;
import org.kamranzafar.halyx.nosql.RecordMapper;
import org.kamranzafar.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class HalyxDocumentStoreTest {

    record Order(String customer, long total) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues("halyx.providers.local.type=memory",
                    "halyx.document-stores.orders.table=orders-table",
                    "halyx.document-stores.orders.sort-key=sk",
                    "halyx.document-stores.orders.indexes.by-customer.partition-attribute=customer",
                    "halyx.document-stores.profiles.partition-key=id");

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
