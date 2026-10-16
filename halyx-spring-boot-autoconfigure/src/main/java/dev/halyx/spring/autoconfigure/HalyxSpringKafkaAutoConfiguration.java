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
package dev.halyx.spring.autoconfigure;

import dev.halyx.Halyx;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Spring for Apache Kafka on a Halyx stream cluster. When {@code halyx.spring-kafka.cluster} names one of the
 * {@code halyx.stream-clusters}, Spring's Kafka producer and consumer factories, and so {@code KafkaTemplate} and
 * {@code @KafkaListener}, connect and log in to that cluster the way Halyx' own producers and consumers do: MSK
 * IAM, Entra ID or Google IAM tokens, refreshed before they expire. Halyx' settings go on top of
 * {@code spring.kafka.*}, so the cluster's bootstrap servers and login win and everything else (serializers,
 * groups, listener settings) stays Spring's. Boot's {@code KafkaAdmin} keeps its own settings: create topics
 * outside the application, as with Halyx' streams.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.kafka.core.DefaultKafkaProducerFactory")
@ConditionalOnProperty(prefix = "halyx.spring-kafka", name = "cluster")
public class HalyxSpringKafkaAutoConfiguration {

    @Bean
    static HalyxSpringKafkaLogin halyxSpringKafkaLogin(ObjectProvider<Halyx> halyx,
                                                       ObjectProvider<HalyxProperties> properties,
                                                       Environment environment) {
        // Read from the Environment: placeholders in @Value aren't resolved this early for a post-processor.
        return new HalyxSpringKafkaLogin(halyx, properties, environment.getProperty("halyx.spring-kafka.cluster", ""));
    }
}
