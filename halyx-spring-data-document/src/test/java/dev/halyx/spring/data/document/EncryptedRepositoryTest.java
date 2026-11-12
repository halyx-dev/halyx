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
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.TableConfig;
import dev.halyx.spring.data.document.repository.HalyxRepositoryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedRepositoryTest {

    record Patient(@PartitionKey String id, String ward, @Encrypted("records") String diagnosis) {
    }

    record Indexed(@PartitionKey String id, @Encrypted("records") String status) {
    }

    record SealedKey(@PartitionKey @Encrypted("records") String id, String name) {
    }

    record NoKeyName(@PartitionKey String id, @Encrypted("") String secret) {
    }

    interface Patients extends DocumentRepository<Patient, String> {
    }

    interface IndexedRepository extends DocumentRepository<Indexed, String> {
    }

    interface SealedKeys extends DocumentRepository<SealedKey, String> {
    }

    interface NoKeyNames extends DocumentRepository<NoKeyName, String> {
    }

    private Halyx halyx;
    private DocumentStore store;
    private CryptoKey records;
    private Function<String, CryptoKey> keys;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        KeyStore keyStore = halyx.provider("mem").keyStore();
        keyStore.unwrap(InMemoryKeys.class).create("records", KeyPurpose.ENCRYPTION);
        records = keyStore.key("records");
        keys = name -> {
            if (!name.equals("records")) {
                throw new IllegalStateException("no key " + name);
            }
            return records;
        };
        store = halyx.provider("mem").documentStore(TableConfig.builder("patients").index("by-status", "status")
                .build());
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    @Test
    void encryptedComponentsAreSealedAndReadBack() {
        Patients patients = new HalyxRepositoryFactory(type -> store, keys).getRepository(Patients.class);

        patients.save(new Patient("p-1", "east", "influenza"));

        assertThat(patients.findById("p-1")).hasValue(new Patient("p-1", "east", "influenza"));
        var stored = store.get(Key.of("p-1")).orElseThrow().document();
        assertThat(stored.getString("ward")).hasValue("east");
        assertThat(stored.get("diagnosis")).isInstanceOf(byte[].class);
        assertThat(store.table().encryptedFields()).as("the bean's own table is unchanged").isEmpty();
    }

    @Test
    void mistakesFailWhenTheRepositoryIsCreated() {
        HalyxRepositoryFactory factory = new HalyxRepositoryFactory(type -> store, keys);

        assertThatThrownBy(() -> factory.getRepository(SealedKeys.class)).hasMessageContaining("keys and the version");
        assertThatThrownBy(() -> factory.getRepository(NoKeyNames.class)).hasMessageContaining("CryptoKey bean");
        assertThatThrownBy(() -> factory.getRepository(IndexedRepository.class))
                .hasMessageContaining("index by-status");
        assertThatThrownBy(() -> new HalyxRepositoryFactory(type -> store).getRepository(Patients.class))
                .hasMessageContaining("key lookup");
    }
}
