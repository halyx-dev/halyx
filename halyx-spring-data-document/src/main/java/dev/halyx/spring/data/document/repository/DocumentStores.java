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

import dev.halyx.keys.CryptoKey;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.spring.data.document.DocumentTable;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

/** The document store of an entity: the {@code DocumentStore} bean its {@link DocumentTable} names, or the only one. */
final class DocumentStores {

    private DocumentStores() {
    }

    /** The {@code CryptoKey} bean an {@code @Encrypted} component names. */
    static CryptoKey key(ListableBeanFactory beans, String name) {
        if (!beans.containsBean(name) || !beans.isTypeMatch(name, CryptoKey.class)) {
            throw new IllegalStateException("@Encrypted(\"" + name + "\") names no CryptoKey bean; configure the key under "
                    + "halyx.keys. CryptoKey beans: " + Arrays.toString(beans.getBeanNamesForType(CryptoKey.class)));
        }
        return beans.getBean(name, CryptoKey.class);
    }

    /** {@code store} with the entity's {@code @Encrypted} components encrypted, each with the key {@code keys} gives. */
    static DocumentStore encrypted(DocumentStore store, EntityModel<?> model, Function<String, CryptoKey> keys) {
        DocumentStore encrypted = store;
        for (Map.Entry<String, String> field : model.encrypted().entrySet()) {
            encrypted = encrypted.withEncryptedField(field.getKey(), keys.apply(field.getValue()));
        }
        return encrypted;
    }

    /** For factories built without keys: any {@code @Encrypted} component is a configuration error. */
    static CryptoKey noKeys(String name) {
        throw new IllegalStateException("@Encrypted(\"" + name + "\") needs a repository factory with a key lookup: "
                + "pass one to the factory's constructor");
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
