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
package dev.halyx.spring.data.document.reactive;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.TableConfig;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.spring.data.document.DocumentRepository;
import dev.halyx.spring.data.document.DocumentTable;
import dev.halyx.spring.data.document.EnableHalyxReactiveRepositories;
import dev.halyx.spring.data.document.EnableHalyxRepositories;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.ReactiveDocumentRepository;
import dev.halyx.spring.data.document.SortKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reactive repository beans from {@link EnableHalyxReactiveRepositories}, without Spring Boot, next to blocking
 * ones from {@link EnableHalyxRepositories} in the same package: each annotation takes only its own kind.
 */
class EnableHalyxReactiveRepositoriesTest {

    @DocumentTable("events")
    public record Event(@PartitionKey String stream, @SortKey String at, String kind) {
    }

    public interface Events extends DocumentRepository<Event, Key> {
        List<Event> findByStreamOrderByAtDesc(String stream);
    }

    public interface ReactiveEvents extends ReactiveDocumentRepository<Event, Key> {
        Flux<Event> findByStreamOrderByAtDesc(String stream);

        Mono<Event> findFirstByStreamOrderByAtDesc(String stream);
    }

    @Configuration(proxyBeanMethods = false)
    static class Stores {
        @Bean(destroyMethod = "close")
        Halyx halyx() {
            return Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        }

        @Bean
        DocumentStore events(Halyx halyx) {
            return halyx.provider("mem").documentStore(TableConfig.builder("events").sortKey("sk").build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Reactive {
        @Bean(destroyMethod = "close")
        ReactiveHalyx reactiveHalyx(Halyx halyx) {
            return ReactiveHalyx.withVirtualThreads(halyx);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableHalyxRepositories(considerNestedRepositories = true)
    @EnableHalyxReactiveRepositories(considerNestedRepositories = true)
    static class Repositories {
    }

    @Test
    void reactiveAndBlockingRepositoriesShareAPackage() {
        try (var context = new AnnotationConfigApplicationContext(Stores.class, Reactive.class, Repositories.class)) {
            ReactiveEvents reactive = context.getBean(ReactiveEvents.class);
            Events blocking = context.getBean(Events.class);
            assertThat(context.getBeansOfType(ReactiveDocumentRepository.class)).hasSize(1);
            assertThat(context.getBeansOfType(DocumentRepository.class)).hasSize(1);

            StepVerifier.create(reactive.saveAll(Flux.just(new Event("s1", "10:00", "open"), new Event("s1", "11:00", "close"))))
                    .expectNextCount(2).verifyComplete();

            StepVerifier.create(reactive.findByStreamOrderByAtDesc("s1").map(Event::kind))
                    .expectNext("close", "open").verifyComplete();
            StepVerifier.create(reactive.findFirstByStreamOrderByAtDesc("s1").map(Event::kind))
                    .expectNext("close").verifyComplete();
            assertThat(blocking.findByStreamOrderByAtDesc("s1")).extracting(Event::kind).containsExactly("close", "open");
        }
    }

    @Test
    void reactiveRepositoriesNeedAReactiveHalyx() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(Stores.class, Repositories.class))
                .isInstanceOf(BeanCreationException.class)
                .rootCause().hasMessageContaining("ReactiveHalyx bean");
    }

    @Test
    void unsupportedQueryShapesFailAtStartup() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(Stores.class, Reactive.class,
                dev.halyx.spring.data.document.reactiveinvalid.Scanning.class))
                .isInstanceOf(BeanCreationException.class)
                .rootCause().hasMessageContaining("findByKind").hasMessageContaining("partition key");
    }
}
