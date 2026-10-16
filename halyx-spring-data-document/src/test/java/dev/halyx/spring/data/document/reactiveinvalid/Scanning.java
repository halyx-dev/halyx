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
package dev.halyx.spring.data.document.reactiveinvalid;

import dev.halyx.nosql.Key;
import dev.halyx.spring.data.document.DocumentTable;
import dev.halyx.spring.data.document.EnableHalyxReactiveRepositories;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.ReactiveDocumentRepository;
import dev.halyx.spring.data.document.SortKey;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/** A reactive repository whose query would scan the table. */
@Configuration(proxyBeanMethods = false)
@EnableHalyxReactiveRepositories(considerNestedRepositories = true)
public class Scanning {

    @DocumentTable("events")
    public record Event(@PartitionKey String stream, @SortKey String at, String kind) {
    }

    public interface ScanningEvents extends ReactiveDocumentRepository<Event, Key> {
        Flux<Event> findByKind(String kind);
    }
}
