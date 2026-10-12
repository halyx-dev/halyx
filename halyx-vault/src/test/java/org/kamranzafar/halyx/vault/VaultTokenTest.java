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
package org.kamranzafar.halyx.vault;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VaultTokenTest {

    private static final Instant ISSUED = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void longLivedTokensRenewThirtySecondsBeforeExpiry() {
        var token = new VaultLogin.Token("t", ISSUED, Optional.of(ISSUED.plusSeconds(3600)));

        assertThat(token.expiringSoon(ISSUED.plusSeconds(3560))).isFalse();
        assertThat(token.expiringSoon(ISSUED.plusSeconds(3575))).isTrue();
    }

    @Test
    void shortLivedTokensRenewInTheirLastFifth() {
        var token = new VaultLogin.Token("t", ISSUED, Optional.of(ISSUED.plusSeconds(10)));

        assertThat(token.expiringSoon(ISSUED.plusSeconds(7))).isFalse();
        assertThat(token.expiringSoon(ISSUED.plusSeconds(9))).isTrue();
    }

    @Test
    void fixedTokensNeverExpire() {
        assertThat(new VaultLogin.Token("t", ISSUED, Optional.empty()).expiringSoon(ISSUED.plusSeconds(1_000_000))).isFalse();
    }
}
