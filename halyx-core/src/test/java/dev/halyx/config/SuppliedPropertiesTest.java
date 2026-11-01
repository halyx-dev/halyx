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
package dev.halyx.config;

import dev.halyx.cache.CacheAuth;
import dev.halyx.events.EventBusConfig;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StreamAuth;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A property or password given as a supplier is asked each time it's read, in the provider config, the resource configs
 * that carry keys, and stream and cache logins; one given as a value stays fixed.
 */
class SuppliedPropertiesTest {

    @Test
    void providerConfigAsksASuppliedPropertyEachTime() {
        AtomicReference<String> key = new AtomicReference<>("first");
        ProviderConfig config = ProviderConfig.of("azure").property("azure.cosmos.key", key::get).region("westeurope");

        assertThat(config.property("azure.cosmos.key")).hasValue("first");
        key.set("rotated");
        assertThat(config.property("azure.cosmos.key")).hasValue("rotated");
        assertThat(config.properties()).isEmpty();
        assertThat(config.suppliedProperties()).containsOnlyKeys("azure.cosmos.key");
        assertThat(config.toString()).doesNotContain("rotated");
    }

    @Test
    void providerConfigKeepsTheLastSettingOfAProperty() {
        ProviderConfig supplied = ProviderConfig.of("azure").property("k", "fixed").property("k", () -> "supplied");
        ProviderConfig fixed = ProviderConfig.of("azure").property("k", () -> "supplied").property("k", "fixed");

        assertThat(supplied.property("k")).hasValue("supplied");
        assertThat(supplied.properties()).isEmpty();
        assertThat(fixed.propertySupplier("k")).hasValueSatisfying(s -> assertThat(s.get()).isEqualTo("fixed"));
        assertThat(fixed.suppliedProperties()).isEmpty();
    }

    @Test
    void streamAndCachePasswordsCanBeSuppliedOrFixed() {
        AtomicReference<String> password = new AtomicReference<>("first");
        var suppliedStream = (StreamAuth.Password) StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", password::get);
        var suppliedCache = (CacheAuth.Password) CacheAuth.password("app", password::get);
        var fixedStream = (StreamAuth.Password) StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", "fixed");
        var fixedCache = (CacheAuth.Password) CacheAuth.password("fixed");

        password.set("rotated");
        assertThat(suppliedStream.password()).isEqualTo("rotated");
        assertThat(suppliedCache.password()).isEqualTo("rotated");
        assertThat(suppliedStream.supplied()).isTrue();
        assertThat(suppliedCache.supplied()).isTrue();
        assertThat(fixedStream.password()).isEqualTo("fixed");
        assertThat(fixedCache.password()).isEqualTo("fixed");
        assertThat(fixedStream.supplied()).isFalse();
        assertThat(fixedCache.supplied()).isFalse();
        assertThat(suppliedStream.toString() + suppliedCache + fixedStream + fixedCache)
                .doesNotContain("rotated").doesNotContain("fixed");
    }

    @Test
    void fixedPasswordsKeepConfigurationsEqual() {
        assertThat(StreamAuth.password(SaslMechanism.PLAIN, "app", "pw"))
                .isEqualTo(new StreamAuth.Password(SaslMechanism.PLAIN, "app", "pw"));
        assertThat(CacheAuth.password("app", "pw")).isEqualTo(CacheAuth.password("app", "pw"))
                .hasSameHashCodeAs(CacheAuth.password("app", "pw"));
        assertThat(StreamAuth.password(SaslMechanism.PLAIN, "app", "pw"))
                .isNotEqualTo(StreamAuth.password(SaslMechanism.PLAIN, "app", "other"));
    }

    @Test
    void eventBusesAndSubscriptionsAskASuppliedPropertyEachTime() {
        AtomicReference<String> key = new AtomicReference<>("first");
        EventBusConfig bus = EventBusConfig.builder("orders").property("azure.eventgrid.access-key", key::get).build();
        SubscriptionConfig subscription = SubscriptionConfig.builder("inbox").property("k", "fixed")
                .property("k", key::get).build();

        key.set("rotated");
        assertThat(bus.property("azure.eventgrid.access-key")).hasValue("rotated");
        assertThat(bus.suppliedProperties()).containsOnlyKeys("azure.eventgrid.access-key");
        assertThat(subscription.property("k")).hasValue("rotated");
        assertThat(subscription.properties()).isEmpty();
    }
}
