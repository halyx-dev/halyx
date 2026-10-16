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
package dev.halyx.spring.cloudrepository;

import dev.halyx.nosql.Key;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.data.HalyxRepositoriesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Boot with a Halyx repository on a real cloud's document store (tier 1): DynamoDB, Cosmos DB or
 * Firestore, configured through {@code halyx.document-stores} and auto-configured, on Boot 3.5 and 4. Uses the
 * run's table with a sort key and its {@code by-status} index. Runs only with
 * {@code -Dhalyx.real.spring.repositories=true}, from the real-cloud scripts' tier 1, which pass the provider type
 * ({@code halyx.real.spring.provider}), its properties ({@code halyx.real.spring.provider-properties}, as
 * {@code key=value,...}) and the table ({@code halyx.real.spring.table}).
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.repositories", matches = "true")
class RealSpringRepositoryIT {

    /** Stands in for the application class: its package is scanned for repositories. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class Application {
    }

    private ApplicationContextRunner runner() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.document-stores.orders.table=" + System.getProperty("halyx.real.spring.table"),
                "halyx.document-stores.orders.sort-key=sk",
                "halyx.document-stores.orders.indexes.by-status.partition-attribute=status",
                "halyx.document-stores.orders.indexes.by-status.sort-attribute=placedAt"));
        for (String pair : System.getProperty("halyx.real.spring.provider-properties", "").split(",")) {
            if (!pair.isBlank()) {
                properties.add("halyx.providers.cloud.properties." + pair.trim());
            }
        }
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class,
                        HalyxRepositoriesAutoConfiguration.class))
                .withUserConfiguration(Application.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    void anAutoConfiguredRepositoryOnTheRealTable() {
        String prefix = "spring-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String customer = prefix + "ada";
        String shipped = prefix + "SHIPPED";
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            CloudOrderRepository orders = context.getBean(CloudOrderRepository.class);
            try {
                CloudOrder first = orders.save(new CloudOrder(customer, "o1", shipped, "2026-10-01", BigDecimal.TEN, null));
                orders.save(new CloudOrder(customer, "o2", shipped, "2026-10-02", BigDecimal.ONE, null));
                orders.save(new CloudOrder(customer, "o3", prefix + "OPEN", "2026-10-03", BigDecimal.ONE, null));

                assertThat(orders.findByCustomerOrderByOrderIdDesc(customer)).extracting(CloudOrder::orderId)
                        .containsExactly("o3", "o2", "o1");
                assertThat(eventually(() -> orders.findByStatusAndPlacedAtGreaterThanEqual(shipped, "2026-10-02"), 1))
                        .extracting(CloudOrder::orderId).containsExactly("o2");

                CloudOrder paid = orders.save(new CloudOrder(customer, "o1", prefix + "PAID", first.placedAt(),
                        first.total(), first.version()));
                assertThatThrownBy(() -> orders.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
                // Compared field by field: Cosmos DB and Firestore keep decimals as doubles, so 10 reads back as 10.0.
                assertThat(orders.findById(Key.of(customer, "o1"))).hasValueSatisfying(found -> {
                    assertThat(found.status()).isEqualTo(paid.status());
                    assertThat(found.total()).isEqualByComparingTo(paid.total());
                    assertThat(found.version()).isEqualTo(paid.version());
                });
            } finally {
                orders.deleteAllById(List.of(Key.of(customer, "o1"), Key.of(customer, "o2"), Key.of(customer, "o3")));
            }
        });
    }

    /** Index reads may lag writes (DynamoDB GSIs): retry until {@code expected} results show up, briefly. */
    private static <T> List<T> eventually(Supplier<List<T>> read, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<T> result = read.get();
        while (result.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(200);
            result = read.get();
        }
        return result;
    }
}
