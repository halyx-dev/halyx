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
package dev.halyx.reactor;

import dev.halyx.CloudContext;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.Codec;
import dev.halyx.events.EventBusConfig;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;

/**
 * Reactive views of one provider's services, made as {@link CloudContext} makes the blocking ones. For SQL,
 * use {@code halyx-r2dbc}, which is reactive already: its {@code R2dbcDatabases} logs connections in with this
 * provider's credentials, starting from {@link #sqlEndpoint}.
 */
public final class ReactiveCloudContext {

    private final CloudContext context;
    private final ReactiveHalyx reactive;

    ReactiveCloudContext(CloudContext context, ReactiveHalyx reactive) {
        this.context = context;
        this.reactive = reactive;
    }

    public String name() {
        return context.name();
    }

    /** The blocking context this wraps. */
    public CloudContext blocking() {
        return context;
    }

    public ReactiveBlobStore blobStore(String bucket) {
        return reactive.wrap(context.blobStore(bucket));
    }

    public ReactiveDocumentStore documentStore(TableConfig config) {
        return reactive.wrap(context.documentStore(config));
    }

    public ReactivePublisher publisher(String topic) {
        return reactive.wrap(context.publisher(topic));
    }

    public ReactivePublisher publisher(TopicConfig config) {
        return reactive.wrap(context.publisher(config));
    }

    public ReactiveSubscription subscription(String name) {
        return reactive.wrap(context.subscription(name));
    }

    public ReactiveSubscription subscription(SubscriptionConfig config) {
        return reactive.wrap(context.subscription(config));
    }

    public ReactiveEventPublisher eventPublisher(String bus) {
        return reactive.wrap(context.eventPublisher(bus));
    }

    public ReactiveEventPublisher eventPublisher(EventBusConfig config) {
        return reactive.wrap(context.eventPublisher(config));
    }

    public ReactiveFunctionInvoker functionInvoker(String function) {
        return reactive.wrap(context.functionInvoker(function));
    }

    public ReactiveFunctionInvoker functionInvoker(FunctionConfig config) {
        return reactive.wrap(context.functionInvoker(config));
    }

    public ReactiveStreamProducer streamProducer(StreamProducerConfig config) {
        return reactive.wrap(context.streamProducer(config));
    }

    public ReactiveStreamConsumer streamConsumer(StreamConsumerConfig config) {
        return reactive.wrap(context.streamConsumer(config));
    }

    public <V> ReactiveCache<V> cache(CacheConfig config, Codec<V> codec) {
        return reactive.wrap(context.cache(config, codec));
    }

    public ReactiveCache<String> cache(CacheConfig config) {
        return reactive.wrap(context.cache(config));
    }

    public ReactiveParameterStore parameterStore(ParameterStoreConfig config) {
        return reactive.wrap(context.parameterStore(config));
    }

    /** Where and how to connect to a database, for R2DBC; see {@code R2dbcDatabases} in {@code halyx-r2dbc}. */
    public SqlEndpoint sqlEndpoint(SqlConfig config) {
        return context.sqlEndpoint(config);
    }

    @Override
    public String toString() {
        return "ReactiveCloudContext[" + context + "]";
    }
}
