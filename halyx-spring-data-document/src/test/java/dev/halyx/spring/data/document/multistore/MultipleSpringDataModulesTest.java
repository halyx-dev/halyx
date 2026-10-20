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
package dev.halyx.spring.data.document.multistore;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.spring.data.document.DocumentRepository;
import dev.halyx.spring.data.document.EnableHalyxRepositories;
import dev.halyx.spring.data.document.PartitionKey;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.keyvalue.annotation.KeySpace;
import org.springframework.data.keyvalue.repository.KeyValueRepository;
import org.springframework.data.map.repository.config.EnableMapRepositories;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Halyx repositories next to another Spring Data module, as during a migration from Spring Data Cosmos or alongside
 * JPA: each module claims only its own repositories (Spring Data's strict configuration mode).
 */
class MultipleSpringDataModulesTest {

    record Event(@PartitionKey String id, String kind) {
    }

    interface EventRepository extends DocumentRepository<Event, String> {
    }

    @KeySpace("notes")
    static class Note {
        @Id
        String id;
        String text;
    }

    /** Another module's repository in the same package: Halyx must leave it alone. */
    interface NoteRepository extends KeyValueRepository<Note, String> {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableHalyxRepositories(considerNestedRepositories = true)
    @EnableMapRepositories(considerNestedRepositories = true)
    static class Application {
        @Bean(destroyMethod = "close")
        Halyx halyx() {
            return Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        }

        @Bean
        DocumentStore events(Halyx halyx) {
            return halyx.provider("mem").documentStore(TableConfig.of("events"));
        }
    }

    @Test
    void eachModuleClaimsOnlyItsOwnRepositories() {
        try (var context = new AnnotationConfigApplicationContext(Application.class)) {
            EventRepository events = context.getBean(EventRepository.class);
            NoteRepository notes = context.getBean(NoteRepository.class);

            events.save(new Event("e1", "opened"));
            Note note = new Note();
            note.id = "n1";
            note.text = "hi";
            notes.save(note);

            assertThat(events.findById("e1")).hasValueSatisfying(e -> assertThat(e.kind()).isEqualTo("opened"));
            assertThat(notes.findById("n1")).hasValueSatisfying(n -> assertThat(n.text).isEqualTo("hi"));
            assertThat(context.getBean("events", DocumentStore.class).get(dev.halyx.nosql.Key.of("e1"))).isPresent();
        }
    }
}
