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
package dev.halyx.spring.reactive;

import dev.halyx.error.NotFoundException;
import dev.halyx.events.CloudEvent;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.reactor.ReactiveBlobStore;
import dev.halyx.reactor.ReactiveDocumentStore;
import dev.halyx.reactor.ReactiveEventPublisher;
import dev.halyx.reactor.ReactiveSubscription;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WebFlux application over the reactive beans, on Boot 3.5 and 4: controllers return the beans' Monos and
 * Fluxes, Halyx's calls run on its scheduler rather than the request's thread, and Boot's health endpoint
 * reports Halyx in a reactive application.
 */
class HalyxWebFluxTest {

    @RestController
    static class InvoiceController {
        final ReactiveBlobStore invoices;
        final ReactiveDocumentStore orders;
        final ReactiveEventPublisher bus;
        final Set<Boolean> virtualThreads = ConcurrentHashMap.newKeySet();

        InvoiceController(@Qualifier("invoices") ReactiveBlobStore invoices, @Qualifier("orders") ReactiveDocumentStore orders,
                          @Qualifier("bus") ReactiveEventPublisher bus) {
            this.invoices = invoices;
            this.orders = orders;
            this.bus = bus;
        }

        @PutMapping(value = "/invoices/{name}", consumes = MediaType.TEXT_PLAIN_VALUE)
        Mono<String> store(@PathVariable String name, @RequestBody String body) {
            return invoices.put(BlobKey.of(name), BlobContent.ofString(body))
                    .doOnNext(r -> virtualThreads.add(Thread.currentThread().isVirtual()))
                    .map(r -> r.key().value());
        }

        @GetMapping(value = "/invoices/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
        Mono<String> read(@PathVariable String name) {
            return invoices.getBytes(BlobKey.of(name)).map(String::new);
        }

        @GetMapping(value = "/customers/{id}/orders", produces = MediaType.TEXT_PLAIN_VALUE)
        Flux<String> orders(@PathVariable String id) {
            return orders.queryAll(Query.partition(id)).map(item -> item.key().sort().orElseThrow() + "\n");
        }

        @PostMapping(value = "/orders/{id}/placed", consumes = MediaType.TEXT_PLAIN_VALUE)
        Mono<Void> placed(@PathVariable String id, @RequestBody String total) {
            return bus.publish(CloudEvent.builder("com.acme.order.placed", "/orders").subject(id)
                    .json("{\"total\":" + total + "}").build());
        }

        @ExceptionHandler(NotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        Mono<String> notFound(NotFoundException e) {
            return Mono.just("not found");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebApp {
        @Bean
        InvoiceController invoiceController(@Qualifier("invoices") ReactiveBlobStore invoices,
                                            @Qualifier("orders") ReactiveDocumentStore orders,
                                            @Qualifier("bus") ReactiveEventPublisher bus) {
            return new InvoiceController(invoices, orders, bus);
        }
    }

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class))
            .withUserConfiguration(WebApp.class)
            .withPropertyValues("halyx.providers.local.type=memory",
                    "halyx.blob-stores.invoices.bucket=acme-invoices",
                    "halyx.document-stores.orders.sort-key=sk",
                    "halyx.event-buses.bus.name=events",
                    "halyx.subscriptions.inbox.topic=events");

    @Test
    void controllersServeTheReactiveBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            client.put().uri("/invoices/inv-1.txt").contentType(MediaType.TEXT_PLAIN).bodyValue("paid")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("inv-1.txt");
            client.get().uri("/invoices/inv-1.txt").exchange()
                    .expectStatus().isOk().expectBody(String.class).isEqualTo("paid");
            client.get().uri("/invoices/missing").exchange().expectStatus().isNotFound();
            assertThat(context.getBean(InvoiceController.class).virtualThreads).containsOnly(true);

            ReactiveDocumentStore orders = context.getBean("ordersReactive", ReactiveDocumentStore.class);
            Flux.range(0, 12).concatMap(i -> orders.put(Key.of("c-1", String.format("o-%02d", i)), Document.of(Map.of("n", i))))
                    .blockLast();
            // The client's decoder splits the streamed text on its newlines.
            java.util.List<String> listed = client.get().uri("/customers/c-1/orders").exchange()
                    .expectStatus().isOk().returnResult(String.class).getResponseBody()
                    .collectList().block(Duration.ofSeconds(5));
            assertThat(listed).hasSize(12).first().isEqualTo("o-00");

            ReactiveSubscription inbox = context.getBean("inboxReactive", ReactiveSubscription.class);
            client.post().uri("/orders/o-42/placed").contentType(MediaType.TEXT_PLAIN).bodyValue("12.5")
                    .exchange().expectStatus().isOk();
            CloudEvent placed = inbox.events(dev.halyx.messaging.ConsumerOptions.DEFAULTS)
                    .doOnNext(d -> d.ack()).map(d -> d.value()).next().block(Duration.ofSeconds(10));
            assertThat(placed.subject()).contains("o-42");
        });
    }

    @Test
    void healthReportsHalyxInAReactiveApplication() throws Exception {
        boolean boot4 = isPresent("org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration");
        Class<?>[] health = boot4
                ? classes("org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration",
                        "org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration",
                        "org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration",
                        "dev.halyx.spring.autoconfigure.health.HalyxHealthContributorAutoConfiguration")
                : classes("org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration",
                        "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration",
                        "org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration",
                        "org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration",
                        "dev.halyx.spring.autoconfigure.actuate.HalyxActuatorHealthContributorAutoConfiguration");
        runner.withConfiguration(AutoConfigurations.of(health)).run(context -> {
            assertThat(context).hasNotFailed();
            Object endpoint = context.getBean(Class.forName(boot4
                    ? "org.springframework.boot.health.actuate.endpoint.HealthEndpoint"
                    : "org.springframework.boot.actuate.health.HealthEndpoint"));
            Object halyx = endpoint.getClass().getMethod("healthForPath", String[].class)
                    .invoke(endpoint, (Object) new String[] {"halyx"});
            assertThat(halyx).as("a halyx health component").isNotNull();
            Object status = halyx.getClass().getMethod("getStatus").invoke(halyx);
            assertThat(status.toString()).isEqualTo("UP");
        });
    }

    private static boolean isPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Class<?>[] classes(String... names) throws ClassNotFoundException {
        Class<?>[] classes = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            classes[i] = Class.forName(names[i]);
        }
        return classes;
    }
}
