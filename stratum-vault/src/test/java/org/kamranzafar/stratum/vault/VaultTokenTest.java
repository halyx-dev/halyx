package org.kamranzafar.stratum.vault;

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
