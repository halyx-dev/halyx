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
