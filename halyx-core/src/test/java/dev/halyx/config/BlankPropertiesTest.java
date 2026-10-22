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

import dev.halyx.cache.CacheConfig;
import dev.halyx.events.EventBusConfig;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEngine;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A blank property value is no value, in the provider config and every resource config: an empty {@code ${VAR:}} in
 * Spring configuration must not reach a driver as a CA file path or a Cloud SQL instance name.
 */
class BlankPropertiesTest {

    @Test
    void providerConfigDropsBlankValuesAndABlankValueRemovesAnEarlierOne() {
        ProviderConfig config = ProviderConfig.of("gcp").property("gcp.project-id", "acme")
                .property("gcp.sql.instance", "").property("redis.ca-certificate", "  ")
                .property("gcp.kafka.principal", "x").property("gcp.kafka.principal", "");

        assertThat(config.properties()).isEqualTo(Map.of("gcp.project-id", "acme"));
        assertThat(config.property("gcp.sql.instance")).isEmpty();
    }

    @Test
    void everyResourceConfigDropsBlankValues() {
        StreamConfig cluster = StreamConfig.builder("b:9092").property("kafka.linger.ms", "").property("a", "1").build();

        assertThat(cluster.properties()).isEqualTo(Map.of("a", "1"));
        assertThat(CacheConfig.builder(URI.create("redis://h:6379")).property("redis.ca-certificate", "")
                .property("a", "1").build().properties()).isEqualTo(Map.of("a", "1"));
        assertThat(SqlConfig.builder(SqlEngine.POSTGRESQL).database("orders").auth(SqlAuth.iam()).property("gcp.sql.instance", "")
                .property("a", "1").build().properties()).isEqualTo(Map.of("a", "1"));
        assertThat(StreamProducerConfig.builder(cluster, "t").property("x", " ").property("a", "1").build().properties())
                .isEqualTo(Map.of("a", "1"));
        assertThat(StreamConsumerConfig.builder(cluster, "t", "g").property("x", "").property("a", "1").build()
                .properties()).isEqualTo(Map.of("a", "1"));
        assertThat(ParameterStoreConfig.secrets().property("x", "").property("a", "1").build().properties())
                .isEqualTo(Map.of("a", "1"));
        assertThat(EventBusConfig.builder("b").property("x", "").property("a", "1").build().properties())
                .isEqualTo(Map.of("a", "1"));
        assertThat(TableConfig.builder("t").property("x", "").property("a", "1").build().properties())
                .isEqualTo(Map.of("a", "1"));
        assertThat(TopicConfig.builder("t").property("x", "").property("a", "1").build().properties())
                .isEqualTo(Map.of("a", "1"));
        assertThat(SubscriptionConfig.builder("s").property("a", "1").property("a", "").build().properties())
                .as("a blank value removes an earlier one").isEmpty();
    }
}
