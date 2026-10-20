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
package dev.halyx.samples.orders;

import dev.halyx.cache.Cache;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What this deployment is: its cloud, the optional features on, the provider behind each Halyx bean, and the settings
 * read from the {@code settings} parameter store. The end-to-end tests read it to know what to check.
 */
@RestController
class DeploymentController {

    static final List<String> FEATURES = List.of("events", "streams", "cache", "sql");

    record Deployment(String cloud, List<String> features, Map<String, String> providers, Map<String, Object> settings) {
    }

    private final ApplicationContext context;
    private final Environment environment;
    private final OrderService orders;

    DeploymentController(ApplicationContext context, Environment environment, OrderService orders) {
        this.context = context;
        this.environment = environment;
        this.orders = orders;
    }

    @GetMapping("/deployment")
    Deployment deployment() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        Map<String, String> providers = new TreeMap<>();
        collect(providers, BlobStore.class, BlobStore::provider);
        collect(providers, DocumentStore.class, DocumentStore::provider);
        collect(providers, Publisher.class, Publisher::provider);
        collect(providers, Subscription.class, Subscription::provider);
        collect(providers, EventPublisher.class, EventPublisher::provider);
        collect(providers, StreamProducer.class, StreamProducer::provider);
        collect(providers, StreamConsumer.class, StreamConsumer::provider);
        collect(providers, Cache.class, Cache::provider);
        collect(providers, ParameterStore.class, ParameterStore::provider);
        return new Deployment(environment.getProperty("orders.cloud", "unknown"),
                FEATURES.stream().filter(active::contains).toList(), providers,
                Map.of("maxLines", orders.maxLines(), "currency", orders.currency()));
    }

    private <T> void collect(Map<String, String> providers, Class<T> type, Function<T, String> provider) {
        context.getBeansOfType(type).forEach((name, bean) -> providers.put(name, provider.apply(bean)));
    }
}
