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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Repository beans from {@link EnableHalyxRepositories}, over {@code DocumentStore} beans. */
class EnableHalyxRepositoriesTest {

    @DocumentTable("events")
    record Event(@PartitionKey String stream, @SortKey String at, String kind) {
    }

    /** No @DocumentTable: stored in the application's only DocumentStore bean. */
    record Note(@PartitionKey String id, String text) {
    }

    @DocumentTable("nowhere")
    record Lost(@PartitionKey String id) {
    }

    interface EventRepository extends DocumentRepository<Event, Key> {
        List<Event> findByStreamOrderByAtDesc(String stream);
    }

    interface NoteRepository extends DocumentRepository<Note, String> {
    }

    interface LostRepository extends DocumentRepository<Lost, String> {
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
    @EnableHalyxRepositories(basePackageClasses = EnableHalyxRepositoriesTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EventRepository.class))
    static class Repositories {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableHalyxRepositories(basePackageClasses = EnableHalyxRepositoriesTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = NoteRepository.class))
    static class NoteRepositories {
        @Bean(destroyMethod = "close")
        Halyx halyx() {
            return Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        }

        @Bean
        DocumentStore notesTable(Halyx halyx) {
            return halyx.provider("mem").documentStore(TableConfig.of("notes"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableHalyxRepositories(basePackageClasses = EnableHalyxRepositoriesTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = LostRepository.class))
    static class LostRepositories {
    }

    @Test
    void repositoriesAreBeansOverTheirDocumentStores() {
        try (var context = new AnnotationConfigApplicationContext(Stores.class, Repositories.class)) {
            EventRepository events = context.getBean(EventRepository.class);
            events.saveAll(List.of(new Event("s1", "2026-01-01T10:00", "open"),
                    new Event("s1", "2026-01-01T11:00", "close")));

            assertThat(events.findByStreamOrderByAtDesc("s1")).extracting(Event::kind).containsExactly("close", "open");
            assertThat(context.getBean("events", DocumentStore.class).get(Key.of("s1", "2026-01-01T10:00"))).isPresent();
        }
    }

    @Test
    void anEntityWithoutDocumentTableUsesTheOnlyDocumentStore() {
        try (var context = new AnnotationConfigApplicationContext(NoteRepositories.class)) {
            context.getBean(NoteRepository.class).save(new Note("n1", "hi"));

            assertThat(context.getBean("notesTable", DocumentStore.class).get(Key.of("n1"))).isPresent();
        }
    }

    @Test
    void anUnknownStoreBeanFailsAtStartup() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(Stores.class, LostRepositories.class))
                .isInstanceOf(BeanCreationException.class)
                .rootCause().hasMessageContaining("document store 'nowhere'").hasMessageContaining("[events]");
    }
}
