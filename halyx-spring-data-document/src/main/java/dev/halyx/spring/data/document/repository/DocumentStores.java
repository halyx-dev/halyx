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
package dev.halyx.spring.data.document.repository;

import dev.halyx.nosql.DocumentStore;
import dev.halyx.spring.data.document.DocumentTable;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.Arrays;

/** The document store of an entity: the {@code DocumentStore} bean its {@link DocumentTable} names, or the only one. */
final class DocumentStores {

    private DocumentStores() {
    }

    static DocumentStore forEntity(ListableBeanFactory beans, Class<?> entity) {
        DocumentTable table = entity.getAnnotation(DocumentTable.class);
        String[] names = beans.getBeanNamesForType(DocumentStore.class);
        if (table != null) {
            if (!Arrays.asList(names).contains(table.value())) {
                throw new IllegalStateException(entity.getName() + " is stored in document store '" + table.value()
                        + "', but there is no DocumentStore bean of that name. DocumentStore beans: "
                        + Arrays.toString(names));
            }
            return beans.getBean(table.value(), DocumentStore.class);
        }
        if (names.length != 1) {
            throw new IllegalStateException(entity.getName() + " needs @DocumentTable(\"<bean name>\"): there are "
                    + names.length + " DocumentStore beans " + Arrays.toString(names));
        }
        return beans.getBean(names[0], DocumentStore.class);
    }
}
