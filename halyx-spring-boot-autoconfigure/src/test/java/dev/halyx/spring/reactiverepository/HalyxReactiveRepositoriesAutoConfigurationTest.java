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
package dev.halyx.spring.reactiverepository;

import dev.halyx.nosql.Key;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.data.HalyxReactiveRepositoriesAutoConfiguration;
import dev.halyx.spring.autoconfigure.data.HalyxRepositoriesAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import dev.halyx.spring.repository.Shipment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive repositories in the application's packages, next to blocking ones, over {@code halyx.document-stores}
 * on the in-memory provider, on Boot 3.5 and 4.
 */
class HalyxReactiveRepositoriesAutoConfigurationTest {

    /** Stands in for the application class: its package is scanned for repositories. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class Application {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class,
                    HalyxRepositoriesAutoConfiguration.class, HalyxReactiveRepositoriesAutoConfiguration.class))
            .withUserConfiguration(Application.class)
            .withPropertyValues(
                    "halyx.providers.local.type=memory",
                    "halyx.document-stores.shipments.sort-key=sk",
                    "halyx.document-stores.shipments.indexes.by-status.partition-attribute=status",
                    "halyx.document-stores.shipments.indexes.by-status.sort-attribute=due");

    @Test
    void reactiveRepositoriesWorkNextToBlockingOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ReactiveShipmentRepository shipments = context.getBean(ReactiveShipmentRepository.class);
            BlockingShipmentRepository blocking = context.getBean(BlockingShipmentRepository.class);

            Shipment first = shipments.save(new Shipment("lhr", "t1", "LATE", LocalDate.of(2026, 3, 1), null)).block();
            StepVerifier.create(shipments.saveAll(Flux.just(
                            new Shipment("lhr", "t2", "LATE", LocalDate.of(2026, 3, 9), null),
                            new Shipment("syd", "t3", "DELIVERED", LocalDate.of(2026, 3, 2), null))))
                    .expectNextCount(2).verifyComplete();

            StepVerifier.create(shipments.findByWarehouseOrderByTrackingIdDesc("lhr").map(Shipment::trackingId))
                    .expectNext("t2", "t1").verifyComplete();
            StepVerifier.create(shipments.findByStatusAndDueLessThan("LATE", "2026-03-05").map(Shipment::trackingId))
                    .expectNext("t1").verifyComplete();
            StepVerifier.create(shipments.existsByStatus("DELIVERED")).expectNext(true).verifyComplete();
            Shipment updated = shipments.save(new Shipment("lhr", "t1", "DELIVERED", first.due(), first.version())).block();
            StepVerifier.create(shipments.save(first)).verifyError(OptimisticLockingFailureException.class);
            StepVerifier.create(shipments.findById(Key.of("lhr", "t1"))).expectNext(updated).verifyComplete();

            assertThat(blocking.findByWarehouseOrderByTrackingIdDesc("lhr")).extracting(Shipment::trackingId)
                    .containsExactly("t2", "t1");
        });
    }

    @Test
    void reactiveRepositoriesFollowTheRepositoriesSwitch() {
        runner.withPropertyValues("halyx.document-repositories.enabled=false").run(context -> assertThat(context)
                .hasNotFailed().doesNotHaveBean(ReactiveShipmentRepository.class).doesNotHaveBean(BlockingShipmentRepository.class));
    }
}
