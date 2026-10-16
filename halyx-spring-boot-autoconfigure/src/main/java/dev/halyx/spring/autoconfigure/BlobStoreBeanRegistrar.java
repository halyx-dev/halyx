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

import dev.halyx.cache.Cache;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Publisher;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.messaging.Subscription;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;

/**
 * Registers one {@link BlobStore} bean per {@code halyx.blob-stores.<name>} entry, one
 * {@code Cache<String>} bean per {@code halyx.caches.<name>} entry and one {@code DataSource} bean per
 * {@code halyx.databases.<name>} entry, each named {@code <name>}, and likewise for document stores, topics,
 * subscriptions and stream producers and consumers, so
 * they can be injected by type or with {@code @Qualifier("<name>")}. Configuration mistakes fail at
 * startup with the offending property named.
 */
class BlobStoreBeanRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    static final String FACTORY_BEAN = "halyxBlobStores";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Binder binder = Binder.get(environment);
        Map<String, HalyxProperties.BlobStoreProperties> stores = binder
                .bind("halyx.blob-stores", Bindable.mapOf(String.class, HalyxProperties.BlobStoreProperties.class))
                .orElse(Map.of());
        Map<String, HalyxProperties.Provider> providers = binder
                .bind("halyx.providers", Bindable.mapOf(String.class, HalyxProperties.Provider.class))
                .orElse(Map.of());
        Map<String, HalyxProperties.CacheProperties> caches = binder
                .bind("halyx.caches", Bindable.mapOf(String.class, HalyxProperties.CacheProperties.class))
                .orElse(Map.of());
        caches.forEach((name, cache) -> {
            String provider = resolveProvider("halyx.caches." + name, cache.provider(), providers);
            RootBeanDefinition definition = new RootBeanDefinition(Cache.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            definition.setFactoryMethodName("cache");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, cache.toCacheConfig(name));
            definition.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, String.class));
            definition.setDescription("Halyx cache " + provider + " " + cache.endpoint());
            registry.registerBeanDefinition(name, definition);
        });
        binder.bind("halyx.parameter-stores", Bindable.mapOf(String.class, HalyxProperties.ParameterStoreProperties.class))
                .orElse(Map.of())
                .forEach((name, store) -> {
                    String provider = resolveProvider("halyx.parameter-stores." + name, store.provider(), providers);
                    RootBeanDefinition definition = new RootBeanDefinition(ParameterStore.class);
                    definition.setFactoryBeanName(FACTORY_BEAN);
                    definition.setFactoryMethodName("parameterStore");
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(1, store.toStoreConfig());
                    definition.setTargetType(ParameterStore.class);
                    definition.setDescription("Halyx " + store.kind().name().toLowerCase() + " store " + provider);
                    registry.registerBeanDefinition(name, definition);
                });
        Map<String, HalyxProperties.DatabaseProperties> databases = binder
                .bind("halyx.databases", Bindable.mapOf(String.class, HalyxProperties.DatabaseProperties.class))
                .orElse(Map.of());
        Set<String> parameterStores = binder
                .bind("halyx.parameter-stores", Bindable.mapOf(String.class, HalyxProperties.ParameterStoreProperties.class))
                .orElse(Map.of()).keySet();
        databases.forEach((name, database) -> {
            String property = "halyx.databases." + name;
            String provider = resolveProvider(property, database.provider(), providers);
            RootBeanDefinition definition = new RootBeanDefinition(DataSource.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            var args = definition.getConstructorArgumentValues();
            args.addIndexedArgumentValue(0, provider);
            args.addIndexedArgumentValue(1, name);
            args.addIndexedArgumentValue(2, database);
            var secret = database.auth().secret();
            if (secret != null) {
                if (secret.store() == null || !parameterStores.contains(secret.store())) {
                    throw new IllegalStateException(property + ".auth.secret.store must name one of halyx.parameter-stores "
                            + parameterStores);
                }
                definition.setFactoryMethodName("dataSourceWithSecret");
                args.addIndexedArgumentValue(3, new RuntimeBeanReference(secret.store()));
            } else {
                definition.setFactoryMethodName("dataSource");
            }
            definition.setTargetType(DataSource.class);
            definition.setPrimary(databases.size() == 1 || database.primary());
            definition.setDescription("Halyx " + database.engine() + " database " + provider + "/" + database.database());
            registry.registerBeanDefinition(name, definition);
        });
        if (ClassUtils.isPresent("dev.halyx.r2dbc.R2dbcDatabases", BlobStoreBeanRegistrar.class.getClassLoader())) {
            R2dbcBeanRegistration.register(binder, registry, providers, parameterStores);
        }
        binder.bind("halyx.document-stores", Bindable.mapOf(String.class, HalyxProperties.DocumentStoreProperties.class))
                .orElse(Map.of())
                .forEach((name, store) -> {
                    String provider = resolveProvider("halyx.document-stores." + name, store.provider(), providers);
                    RootBeanDefinition definition = new RootBeanDefinition(DocumentStore.class);
                    definition.setFactoryBeanName(FACTORY_BEAN);
                    definition.setFactoryMethodName("documentStore");
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(1, name);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, store);
                    definition.setTargetType(DocumentStore.class);
                    definition.setDescription("Halyx document store " + provider + "/" + store.toTableConfig(name).name());
                    registry.registerBeanDefinition(name, definition);
                });
        binder.bind("halyx.topics", Bindable.mapOf(String.class, HalyxProperties.TopicProperties.class))
                .orElse(Map.of())
                .forEach((name, topic) -> registry.registerBeanDefinition(name, messagingBean(Publisher.class,
                        "halyx.topics." + name, name, topic.provider(), topic, topic.claimCheck(), "publisher",
                        providers, stores.keySet())));
        binder.bind("halyx.event-buses", Bindable.mapOf(String.class, HalyxProperties.EventBusProperties.class))
                .orElse(Map.of())
                .forEach((name, bus) -> {
                    String provider = resolveProvider("halyx.event-buses." + name, bus.provider(), providers);
                    RootBeanDefinition definition = new RootBeanDefinition(EventPublisher.class);
                    definition.setFactoryBeanName(FACTORY_BEAN);
                    definition.setFactoryMethodName("eventPublisher");
                    var args = definition.getConstructorArgumentValues();
                    args.addIndexedArgumentValue(0, provider);
                    args.addIndexedArgumentValue(1, name);
                    args.addIndexedArgumentValue(2, bus);
                    definition.setTargetType(EventPublisher.class);
                    definition.setDescription("Halyx event bus " + provider + "/" + name);
                    registry.registerBeanDefinition(name, definition);
                });
        binder.bind("halyx.subscriptions", Bindable.mapOf(String.class, HalyxProperties.SubscriptionProperties.class))
                .orElse(Map.of())
                .forEach((name, subscription) -> registry.registerBeanDefinition(name, messagingBean(Subscription.class,
                        "halyx.subscriptions." + name, name, subscription.provider(), subscription,
                        subscription.claimCheck(), "subscription", providers, stores.keySet())));
        Map<String, HalyxProperties.StreamClusterProperties> clusters = binder
                .bind("halyx.stream-clusters", Bindable.mapOf(String.class, HalyxProperties.StreamClusterProperties.class))
                .orElse(Map.of());
        binder.bind("halyx.stream-producers", Bindable.mapOf(String.class, HalyxProperties.StreamProducerProperties.class))
                .orElse(Map.of())
                .forEach((name, producer) -> registry.registerBeanDefinition(name, streamBean(StreamProducer.class,
                        "halyx.stream-producers." + name, name, producer.cluster(), producer, producer.claimCheck(),
                        "streamProducer", clusters, providers, stores.keySet())));
        binder.bind("halyx.stream-consumers", Bindable.mapOf(String.class, HalyxProperties.StreamConsumerProperties.class))
                .orElse(Map.of())
                .forEach((name, consumer) -> {
                    RootBeanDefinition definition = streamBean(StreamConsumer.class, "halyx.stream-consumers." + name,
                            name, consumer.cluster(), consumer, consumer.claimCheck(), "streamConsumer", clusters,
                            providers, stores.keySet());
                    // Closing leaves the consumer group, handing its partitions to the other members.
                    definition.setDestroyMethodName(AbstractBeanDefinition.INFER_METHOD);
                    registry.registerBeanDefinition(name, definition);
                });
        stores.forEach((name, store) -> {
            String provider = resolveProvider("halyx.blob-stores." + name, store.provider(), providers);
            String bucket = store.bucket() == null || store.bucket().isBlank() ? name : store.bucket();
            RootBeanDefinition definition = new RootBeanDefinition(BlobStore.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            definition.setFactoryMethodName("blobStore");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, bucket);
            definition.setTargetType(BlobStore.class);
            definition.setDescription("Halyx blob store " + provider + "/" + bucket);
            registry.registerBeanDefinition(name, definition);
        });
    }

    /** A Publisher or Subscription bean; with a claim check, its blob store bean is injected too. */
    private static RootBeanDefinition messagingBean(Class<?> type, String property, String name, String provider,
                                                    Object properties, HalyxProperties.ClaimCheckProperties claimCheck,
                                                    String factoryMethod, Map<String, HalyxProperties.Provider> providers,
                                                    Set<String> blobStores) {
        String resolved = resolveProvider(property, provider, providers);
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.setFactoryBeanName(FACTORY_BEAN);
        var args = definition.getConstructorArgumentValues();
        args.addIndexedArgumentValue(0, resolved);
        args.addIndexedArgumentValue(1, name);
        args.addIndexedArgumentValue(2, properties);
        if (claimCheck != null) {
            if (claimCheck.blobStore() == null || !blobStores.contains(claimCheck.blobStore())) {
                throw new IllegalStateException(property + ".claim-check.blob-store must name one of halyx.blob-stores "
                        + blobStores);
            }
            definition.setFactoryMethodName(factoryMethod + "WithClaimCheck");
            args.addIndexedArgumentValue(3, new RuntimeBeanReference(claimCheck.blobStore()));
        } else {
            definition.setFactoryMethodName(factoryMethod);
        }
        definition.setTargetType(type);
        definition.setDescription("Halyx " + factoryMethod + " " + resolved + "/" + name);
        return definition;
    }

    /** A StreamProducer or StreamConsumer bean on its cluster; with a claim check, its blob store bean is injected too. */
    private static RootBeanDefinition streamBean(Class<?> type, String property, String name, String cluster,
                                                 Object properties, HalyxProperties.ClaimCheckProperties claimCheck,
                                                 String factoryMethod,
                                                 Map<String, HalyxProperties.StreamClusterProperties> clusters,
                                                 Map<String, HalyxProperties.Provider> providers, Set<String> blobStores) {
        String clusterName = resolveCluster(property, cluster, clusters);
        HalyxProperties.StreamClusterProperties clusterProperties = clusters.get(clusterName);
        String provider = resolveProvider("halyx.stream-clusters." + clusterName, clusterProperties.provider(), providers);
        StreamConfig streamConfig = clusterProperties.toStreamConfig(clusterName);
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.setFactoryBeanName(FACTORY_BEAN);
        var args = definition.getConstructorArgumentValues();
        args.addIndexedArgumentValue(0, provider);
        args.addIndexedArgumentValue(1, name);
        args.addIndexedArgumentValue(2, properties);
        args.addIndexedArgumentValue(3, streamConfig);
        if (claimCheck != null) {
            if (claimCheck.blobStore() == null || !blobStores.contains(claimCheck.blobStore())) {
                throw new IllegalStateException(property + ".claim-check.blob-store must name one of halyx.blob-stores "
                        + blobStores);
            }
            definition.setFactoryMethodName(factoryMethod + "WithClaimCheck");
            args.addIndexedArgumentValue(4, new RuntimeBeanReference(claimCheck.blobStore()));
        } else {
            definition.setFactoryMethodName(factoryMethod);
        }
        definition.setTargetType(type);
        definition.setDescription("Halyx " + factoryMethod + " " + provider + "/" + clusterName + "/" + name);
        return definition;
    }

    static String resolveCluster(String property, String cluster,
                                 Map<String, HalyxProperties.StreamClusterProperties> clusters) {
        if (cluster == null) {
            if (clusters.size() == 1) {
                return clusters.keySet().iterator().next();
            }
            throw new IllegalStateException(property + ".cluster is required when " + clusters.size()
                    + " stream clusters are configured " + clusters.keySet());
        }
        if (!clusters.containsKey(cluster)) {
            throw new IllegalStateException(property + ".cluster refers to unknown stream cluster '" + cluster
                    + "'. Configured clusters: " + clusters.keySet());
        }
        return cluster;
    }

    static String resolveProvider(String property, String provider,
                                          Map<String, HalyxProperties.Provider> providers) {
        if (provider == null) {
            if (providers.size() == 1) {
                return providers.keySet().iterator().next();
            }
            throw new IllegalStateException(property + ".provider is required when "
                    + providers.size() + " providers are configured " + providers.keySet());
        }
        if (!providers.containsKey(provider)) {
            throw new IllegalStateException(property + ".provider refers to unknown provider '"
                    + provider + "'. Configured providers: " + providers.keySet());
        }
        return provider;
    }
}
