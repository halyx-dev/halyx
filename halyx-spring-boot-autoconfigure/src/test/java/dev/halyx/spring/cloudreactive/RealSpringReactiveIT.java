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
package dev.halyx.spring.cloudreactive;

import dev.halyx.events.CloudEvent;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.nosql.Key;
import dev.halyx.reactor.Delivery;
import dev.halyx.reactor.ReactiveBlobStore;
import dev.halyx.reactor.ReactiveEventPublisher;
import dev.halyx.reactor.ReactiveSubscription;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.data.HalyxReactiveRepositoriesAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import dev.halyx.spring.cloudrepository.CloudOrder;
import dev.halyx.storage.BlobKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with halyx-reactor on a real cloud (tier 1), on Boot 3.5 and 4: a WebFlux application over the
 * run's bucket, a reactive repository on its table, and events through its bus and inbox, all through the reactive
 * beans. Runs only with {@code -Dhalyx.real.spring.reactive=true}, from the real-cloud scripts' tier 1, which pass
 * the provider ({@code halyx.real.spring.provider}, {@code .provider-properties}), the bucket
 * ({@code halyx.real.spring.bucket}), the table ({@code .table}), the event bus ({@code .event-bus}, with
 * {@code .event-bus-properties}) and its inbox ({@code .inbox}, with {@code .inbox-topic} and {@code .inbox-properties}).
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.reactive", matches = "true")
class RealSpringReactiveIT {

    private static final String SOURCE = "/halyx-tck/spring-reactive-" + UUID.randomUUID().toString().substring(0, 8);

    @RestController
    static class InvoiceController {
        final ReactiveBlobStore invoices;

        InvoiceController(@Qualifier("invoices") ReactiveBlobStore invoices) {
            this.invoices = invoices;
        }

        @PutMapping(value = "/invoices/{name}", consumes = MediaType.TEXT_PLAIN_VALUE)
        Mono<String> store(@PathVariable String name, @RequestBody String body) {
            return invoices.put(BlobKey.of(name), dev.halyx.storage.BlobContent.ofString(body)).map(r -> r.key().value());
        }

        @GetMapping(value = "/invoices/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
        Mono<String> read(@PathVariable String name) {
            return invoices.getBytes(BlobKey.of(name)).map(String::new);
        }
    }

    /** Stands in for the application class: its package is scanned for repositories. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    @EnableWebFlux
    static class Application {
        @Bean
        InvoiceController invoiceController(@Qualifier("invoices") ReactiveBlobStore invoices) {
            return new InvoiceController(invoices);
        }
    }

    private static void addAll(List<String> properties, String prefix, String pairs) {
        for (String pair : pairs.split(",")) {
            if (!pair.isBlank()) {
                properties.add(prefix + pair.trim());
            }
        }
    }

    private ReactiveWebApplicationContextRunner runner() {
        List<String> properties = new ArrayList<>(List.of(
                "halyx.providers.cloud.type=" + System.getProperty("halyx.real.spring.provider"),
                "halyx.blob-stores.invoices.bucket=" + System.getProperty("halyx.real.spring.bucket"),
                "halyx.document-stores.orders.table=" + System.getProperty("halyx.real.spring.table"),
                "halyx.document-stores.orders.sort-key=sk",
                "halyx.document-stores.orders.indexes.by-status.partition-attribute=status",
                "halyx.document-stores.orders.indexes.by-status.sort-attribute=placedAt",
                "halyx.event-buses.events.name=" + System.getProperty("halyx.real.spring.event-bus"),
                "halyx.subscriptions.inbox.name=" + System.getProperty("halyx.real.spring.inbox")));
        String topic = System.getProperty("halyx.real.spring.inbox-topic", "");
        if (!topic.isBlank()) {
            properties.add("halyx.subscriptions.inbox.topic=" + topic);
        }
        addAll(properties, "halyx.providers.cloud.properties.", System.getProperty("halyx.real.spring.provider-properties", ""));
        addAll(properties, "halyx.event-buses.events.properties.", System.getProperty("halyx.real.spring.event-bus-properties", ""));
        addAll(properties, "halyx.subscriptions.inbox.properties.", System.getProperty("halyx.real.spring.inbox-properties", ""));
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class,
                        HalyxReactiveRepositoriesAutoConfiguration.class))
                .withUserConfiguration(Application.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    @Test
    void aWebFluxApplicationOverTheRealBucket() {
        String name = "spring-reactive-" + UUID.randomUUID().toString().substring(0, 8);
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ReactiveBlobStore invoices = context.getBean("invoicesReactive", ReactiveBlobStore.class);
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            byte[] big = new byte[300 * 1024];
            new Random(7).nextBytes(big);
            try {
                client.put().uri("/invoices/" + name + ".txt").contentType(MediaType.TEXT_PLAIN).bodyValue("paid")
                        .exchange().expectStatus().isOk();
                client.get().uri("/invoices/" + name + ".txt").exchange()
                        .expectStatus().isOk().expectBody(String.class).isEqualTo("paid");

                // A body streamed up in chunks, and down again as the subscriber asks.
                Flux<ByteBuffer> chunks = Flux.range(0, 30).map(i -> ByteBuffer.wrap(big, i * 10240, 10240));
                invoices.put(BlobKey.of(name + ".bin"), chunks, dev.halyx.storage.PutOptions.defaults()).block(Duration.ofMinutes(1));
                byte[] read = invoices.getBody(BlobKey.of(name + ".bin")).reduce(new java.io.ByteArrayOutputStream(), (out, chunk) -> {
                    byte[] b = new byte[chunk.remaining()];
                    chunk.get(b);
                    out.writeBytes(b);
                    return out;
                }).map(java.io.ByteArrayOutputStream::toByteArray).block(Duration.ofMinutes(1));
                assertThat(read).isEqualTo(big);
                StepVerifier.create(invoices.listAll(name).map(s -> s.key().value()).sort())
                        .expectNext(name + ".bin", name + ".txt").verifyComplete();
            } finally {
                invoices.deleteAll(List.of(BlobKey.of(name + ".txt"), BlobKey.of(name + ".bin"))).block(Duration.ofMinutes(1));
            }
        });
    }

    @Test
    void aReactiveRepositoryOnTheRealTable() {
        String prefix = "spring-rx-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String customer = prefix + "ada";
        String shipped = prefix + "SHIPPED";
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ReactiveCloudOrderRepository orders = context.getBean(ReactiveCloudOrderRepository.class);
            try {
                CloudOrder first = orders.save(new CloudOrder(customer, "o1", shipped, "2026-10-01", BigDecimal.TEN, null))
                        .block(Duration.ofMinutes(1));
                StepVerifier.create(orders.saveAll(Flux.just(
                                new CloudOrder(customer, "o2", shipped, "2026-10-02", BigDecimal.ONE, null),
                                new CloudOrder(customer, "o3", prefix + "OPEN", "2026-10-03", BigDecimal.ONE, null))))
                        .expectNextCount(2).verifyComplete();

                StepVerifier.create(orders.findByCustomerOrderByOrderIdDesc(customer).map(CloudOrder::orderId))
                        .expectNext("o3", "o2", "o1").verifyComplete();
                assertThat(eventually(() -> orders.findByStatusAndPlacedAtGreaterThanEqual(shipped, "2026-10-02"), 1))
                        .extracting(CloudOrder::orderId).containsExactly("o2");

                orders.save(new CloudOrder(customer, "o1", prefix + "PAID", first.placedAt(), first.total(), first.version()))
                        .block(Duration.ofMinutes(1));
                StepVerifier.create(orders.save(first)).verifyError(OptimisticLockingFailureException.class);
            } finally {
                orders.deleteAllById(List.of(Key.of(customer, "o1"), Key.of(customer, "o2"), Key.of(customer, "o3")))
                        .block(Duration.ofMinutes(1));
            }
        });
    }

    @Test
    void eventsThroughTheReactiveBeans() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ReactiveEventPublisher events = context.getBean("eventsReactive", ReactiveEventPublisher.class);
            ReactiveSubscription inbox = context.getBean("inboxReactive", ReactiveSubscription.class);
            CloudEvent placed = CloudEvent.builder("com.acme.order.placed", SOURCE).subject("o-42")
                    .json("{\"total\":12.5}").extension("tenant", "acme").build();

            Mono<CloudEvent> received = inbox.events(ConsumerOptions.DEFAULTS)
                    .doOnNext(Delivery::ack)
                    .map(Delivery::value)
                    .filter(event -> event.source().equals(SOURCE))
                    .next();
            CloudEvent got = events.publish(placed).then(received).block(Duration.ofSeconds(120));

            assertThat(got.id()).isEqualTo(placed.id());
            assertThat(got.subject()).contains("o-42");
            assertThat(got.extension("tenant")).contains("acme");
        });
    }

    /** Index reads may lag writes (DynamoDB GSIs): retry until {@code expected} results show up, briefly. */
    private static <T> List<T> eventually(Supplier<Flux<T>> read, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<T> result = read.get().collectList().block(Duration.ofMinutes(1));
        while (result.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(200);
            result = read.get().collectList().block(Duration.ofMinutes(1));
        }
        return result;
    }
}
