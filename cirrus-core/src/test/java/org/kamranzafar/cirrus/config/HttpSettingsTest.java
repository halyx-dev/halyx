package org.kamranzafar.cirrus.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSettingsTest {

    @Test
    void defaultsApplyWithoutProperties() {
        assertThat(HttpSettings.from(ProviderConfig.of("aws"))).isEqualTo(HttpSettings.DEFAULTS);
    }

    @Test
    void readsPropertiesInSeveralDurationFormats() {
        var config = ProviderConfig.of("aws")
                .property(HttpSettings.MAX_CONNECTIONS, "500")
                .property(HttpSettings.CONNECT_TIMEOUT, "500ms")
                .property(HttpSettings.READ_TIMEOUT, "2m")
                .property(HttpSettings.IDLE_TIMEOUT, "PT30S")
                .property(HttpSettings.ACQUIRE_TIMEOUT, "1h");

        var settings = HttpSettings.from(config);

        assertThat(settings.maxConnections()).isEqualTo(500);
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.acquireTimeout()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsBadValues() {
        assertThatThrownBy(() -> HttpSettings.from(ProviderConfig.of("aws").property(HttpSettings.READ_TIMEOUT, "soon")))
                .hasMessageContaining("Invalid duration 'soon'");
        assertThatThrownBy(() -> HttpSettings.from(ProviderConfig.of("aws").property(HttpSettings.MAX_CONNECTIONS, "0")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
