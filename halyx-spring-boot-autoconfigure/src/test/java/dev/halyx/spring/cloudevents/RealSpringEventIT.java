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
package dev.halyx.spring.cloudevents;

import dev.halyx.events.CloudEvent;
import dev.halyx.events.EventPublisher;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.messaging.HalyxListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with a real cloud's event router (tier 1): an {@code EventPublisher} bean from
 * {@code halyx.event-buses}, and a {@code @HalyxListener} method taking a {@code CloudEvent}, on the subscription the
 * router delivers to, on Boot 3.5 and 4. Runs only with {@code -Dhalyx.real.spring.events=true}, from the
 * real-cloud scripts' tier 1, which pass the provider type and properties, the bus
 * ({@code halyx.real.spring.event-bus}, with {@code .event-bus-properties}) and the inbox
 * ({@code halyx.real.spring.inbox}, with {@code .inbox-topic} and {@code .inbox-properties}). Its events' source
 * starts with {@code /halyx-tck/}, which the run's routing matches; other events in the inbox are ignored.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.events", matches = "true")
class RealSpringEventIT {

    static final String SOURCE = "/halyx-tck/spring-" + UUID.randomUUID().toString().substring(0, 8);

    static class Handler {
        final Map<String, CloudEvent> received = new ConcurrentHashMap<>();

        @HalyxListener("inbox")
        void onEvent(CloudEvent event) {
            if (event.source().equals(SOURCE)) {
                received.put(event.id(), event);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfig {
        @Bean
        Handler eventHandler() {
            return new Handler();
        }
    }

    private static void addAll(List<String> properties, String prefix, String pairs) {
        for (String pair : pairs.split(",")) {
            if (!pair.isBlank()) {
                properties.add(prefix + pair.trim());
            }
        }
    }

    private ApplicationContextRunner runner() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.event-buses.orders.name=" + System.getProperty("halyx.real.spring.event-bus"),
                "halyx.subscriptions.inbox.name=" + System.getProperty("halyx.real.spring.inbox")));
        String topic = System.getProperty("halyx.real.spring.inbox-topic", "");
        if (!topic.isBlank()) {
            properties.add("halyx.subscriptions.inbox.topic=" + topic);
        }
        addAll(properties, "halyx.providers.cloud.properties.", System.getProperty("halyx.real.spring.provider-properties", ""));
        addAll(properties, "halyx.event-buses.orders.properties.", System.getProperty("halyx.real.spring.event-bus-properties", ""));
        addAll(properties, "halyx.subscriptions.inbox.properties.", System.getProperty("halyx.real.spring.inbox-properties", ""));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
                .withUserConfiguration(HandlerConfig.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    void publishedEventsReachACloudEventListener() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            EventPublisher orders = context.getBean("orders", EventPublisher.class);
            CloudEvent placed = CloudEvent.builder("com.acme.order.placed", SOURCE).subject("o-42")
                    .json("{\"total\":12.5}").extension("tenant", "acme").build();
            // With data: Eventarc can't deliver events without data to Pub/Sub.
            CloudEvent shipped = CloudEvent.builder("com.acme.order.shipped", SOURCE).data("text/plain", "acme").build();
            orders.publishAll(List.of(placed, shipped));

            Handler handler = context.getBean(Handler.class);
            Instant deadline = Instant.now().plusSeconds(90);
            while (handler.received.size() < 2 && Instant.now().isBefore(deadline)) {
                Thread.sleep(200);
            }
            assertThat(handler.received).containsOnlyKeys(placed.id(), shipped.id());
            CloudEvent received = handler.received.get(placed.id());
            assertThat(received.subject()).contains("o-42");
            assertThat(received.extension("tenant")).contains("acme");
        });
    }
}
