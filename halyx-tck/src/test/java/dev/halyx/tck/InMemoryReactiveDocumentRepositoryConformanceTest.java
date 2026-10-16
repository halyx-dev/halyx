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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.reactor.ReactiveHalyx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

class InMemoryReactiveDocumentRepositoryConformanceTest extends ReactiveDocumentRepositoryConformanceTest {

    private static Halyx halyx;
    private static ReactiveHalyx reactive;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        reactive = ReactiveHalyx.withVirtualThreads(halyx);
    }

    @AfterAll
    static void stop() {
        reactive.close();
        halyx.close();
    }

    @Override
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return halyx.provider("test").documentStore(DocumentStoreConformanceTest.indexes(table, sorted).build());
    }

    @Override
    protected ReactiveHalyx reactive() {
        return reactive;
    }
}
