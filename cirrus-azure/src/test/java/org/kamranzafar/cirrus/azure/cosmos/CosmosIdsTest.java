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
package org.kamranzafar.cirrus.azure.cosmos;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CosmosIdsTest {

    private static final List<String> KEYS = List.of("plain", "a/b", "a b", "a%2Fb", "a~2Fb", "ünïcode", "x?y#z",
            "back\\slash", "order#2", "order~232", "order%232", "~", "%", "~7E", "%25");

    @Test
    void idsHaveNoCharactersCosmosForbidsOrTheGatewayDecodes() {
        for (String key : KEYS) {
            assertThat(CosmosDocumentStoreDriver.encodeId(key)).doesNotContain("/", "\\", "?", "#", "%");
        }
    }

    @Test
    void distinctKeysGetDistinctIds() {
        Set<String> ids = new HashSet<>();
        KEYS.forEach(key -> ids.add(CosmosDocumentStoreDriver.encodeId(key)));
        assertThat(ids).hasSize(KEYS.size());
    }

    @Test
    void plainKeysAreUnchanged() {
        assertThat(CosmosDocumentStoreDriver.encodeId("order-2026_10")).isEqualTo("order-2026_10");
    }
}
