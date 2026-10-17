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
package dev.halyx.spring.cloudmessaging;

import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.messaging.HalyxListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with a real cloud's messaging (tier 1): a {@code Publisher} bean from {@code halyx.topics} and a
 * {@code @HalyxListener} on a {@code halyx.subscriptions} bean, with a claim check through the run's bucket for a body
 * over the service's message limit, and a failing first delivery retried, on Boot 3.5 and 4. Runs only with
 * {@code -Dhalyx.real.spring.messaging=true}, from the real-cloud scripts' tier 1, which pass the provider type and
 * properties, the topic ({@code halyx.real.spring.topic}, with {@code .topic-properties}), the subscription
 * delivering it ({@code halyx.real.spring.subscription}, with {@code .subscription-properties}) and the bucket
 * ({@code halyx.real.spring.bucket}). Its messages carry this run's marker; others on the subscription are acked
 * and ignored.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.messaging", matches = "true")
class RealSpringMessagingIT {

    static final String MARKER = "spring-" + UUID.randomUUID().toString().substring(0, 8);

    static class Handler {
        final Map<String, String> received = new ConcurrentHashMap<>();
        final AtomicInteger failuresLeft = new AtomicInteger(1);

        @HalyxListener("billing")
        void onOrder(ReceivedMessage message) {
            if (!MARKER.equals(message.attributes().get("run"))) {
                return;
            }
            String id = message.attributes().get("order");
            if (id.equals("flaky") && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("try again");
            }
            received.put(id, message.bodyAsString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfig {
        @Bean
        Handler orderHandler() {
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
                "halyx.blob-stores.claims.bucket=" + System.getProperty("halyx.real.spring.bucket"),
                "halyx.topics.orders.name=" + System.getProperty("halyx.real.spring.topic"),
                "halyx.topics.orders.claim-check.blob-store=claims",
                "halyx.topics.orders.claim-check.threshold=1024",
                "halyx.topics.orders.claim-check.prefix=" + MARKER + "/",
                "halyx.subscriptions.billing.name=" + System.getProperty("halyx.real.spring.subscription"),
                "halyx.subscriptions.billing.claim-check.blob-store=claims",
                "halyx.subscriptions.billing.listener.initial-backoff=PT1S"));
        addAll(properties, "halyx.providers.cloud.properties.", System.getProperty("halyx.real.spring.provider-properties", ""));
        addAll(properties, "halyx.topics.orders.properties.", System.getProperty("halyx.real.spring.topic-properties", ""));
        addAll(properties, "halyx.subscriptions.billing.properties.",
                System.getProperty("halyx.real.spring.subscription-properties", ""));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
                .withUserConfiguration(HandlerConfig.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    private static OutgoingMessage order(String id, String body) {
        return OutgoingMessage.builder(body.getBytes(StandardCharsets.UTF_8)).attribute("run", MARKER)
                .attribute("order", id).build();
    }

    @Test
    void listenersReceiveRetriedAndClaimCheckedMessages() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            Publisher orders = context.getBean("orders", Publisher.class);
            String large = "x".repeat(300 * 1024); // over SNS's and Service Bus Standard's 256 KB
            orders.publishAll(List.of(order("small", "order 1"), order("flaky", "order 2"), order("large", large)));

            Handler handler = context.getBean(Handler.class);
            Instant deadline = Instant.now().plusSeconds(120);
            while (handler.received.size() < 3 && Instant.now().isBefore(deadline)) {
                Thread.sleep(200);
            }
            assertThat(handler.received).containsEntry("small", "order 1").containsEntry("flaky", "order 2");
            assertThat(handler.received.get("large")).hasSize(large.length());
        });
    }
}
