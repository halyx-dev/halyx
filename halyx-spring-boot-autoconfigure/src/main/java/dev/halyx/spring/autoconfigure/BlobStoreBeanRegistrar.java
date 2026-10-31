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
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.messaging.Publisher;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.messaging.Subscription;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
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
 * Registers one {@link BlobStore} bean per {@code halyx.blob-stores.<name>} entry, one {@code Cache} bean per
 * {@code halyx.caches.<name>} entry and one {@code DataSource} bean per {@code halyx.databases.<name>} entry, each
 * named {@code <name>}, and likewise for document stores, topics, event buses, subscriptions and stream producers and
 * consumers, so they can be injected by type or with {@code @Qualifier("<name>")}. Configuration mistakes fail at
 * startup with the offending property named.
 *
 * <p>Providers are resolved when each bean is created, against the {@code Halyx} bean, so resources can use
 * providers that a {@code HalyxBuilderCustomizer} or an application's own {@code Halyx} bean registers.
 */
class BlobStoreBeanRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, BeanClassLoaderAware {

    static final String FACTORY_BEAN = HalyxBlobStores.BEAN_NAME;

    private Environment environment;
    private ClassLoader classLoader = BlobStoreBeanRegistrar.class.getClassLoader();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Binder binder = Binder.get(environment);
        HalyxBeanNames names = new HalyxBeanNames(registry);
        Map<String, HalyxProperties.BlobStoreProperties> stores = binder
                .bind("halyx.blob-stores", Bindable.mapOf(String.class, HalyxProperties.BlobStoreProperties.class))
                .orElse(Map.of());
        binder.bind("halyx.caches", Bindable.mapOf(String.class, HalyxProperties.CacheProperties.class))
                .orElse(Map.of())
                .forEach((name, cache) -> names.register(name, "halyx.caches." + name, "namespace", cacheBean(name, cache)));
        binder.bind("halyx.parameter-stores", Bindable.mapOf(String.class, HalyxProperties.ParameterStoreProperties.class))
                .orElse(Map.of())
                .forEach((name, store) -> {
                    RootBeanDefinition definition = serviceBean(ParameterStore.class, "parameterStore",
                            "halyx.parameter-stores." + name, store.provider());
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, store.toStoreConfig());
                    definition.setDescription("Halyx " + store.kind().name().toLowerCase() + " store " + name);
                    names.register(name, "halyx.parameter-stores." + name, null, definition);
                });
        Map<String, HalyxProperties.DatabaseProperties> databases = binder
                .bind("halyx.databases", Bindable.mapOf(String.class, HalyxProperties.DatabaseProperties.class))
                .orElse(Map.of());
        Set<String> parameterStores = binder
                .bind("halyx.parameter-stores", Bindable.mapOf(String.class, HalyxProperties.ParameterStoreProperties.class))
                .orElse(Map.of()).keySet();
        databases.forEach((name, database) -> {
            String property = "halyx.databases." + name;
            RootBeanDefinition definition = serviceBean(DataSource.class, "dataSource", property, database.provider());
            var args = definition.getConstructorArgumentValues();
            args.addIndexedArgumentValue(2, name);
            args.addIndexedArgumentValue(3, database);
            var secret = database.auth().secret();
            if (secret != null) {
                if (secret.store() == null || !parameterStores.contains(secret.store())) {
                    throw new IllegalStateException(property + ".auth.secret.store must name one of halyx.parameter-stores "
                            + parameterStores);
                }
                definition.setFactoryMethodName("dataSourceWithSecret");
                args.addIndexedArgumentValue(4, new RuntimeBeanReference(secret.store()));
            }
            definition.setPrimary(databases.size() == 1 || database.primary());
            definition.setDescription("Halyx " + database.engine() + " database " + name + "/" + database.database());
            names.register(name, property, null, definition);
        });
        if (!databases.isEmpty()) {
            IgnoredSpringProperties.warnIfSet(environment, "spring.datasource", "halyx.databases " + databases.keySet()
                    + " defines the DataSource beans, so Spring Boot does not create its own");
        }
        boolean r2dbcConfigured = !binder.bind(HalyxR2dbcDatabases.GROUP,
                Bindable.mapOf(String.class, HalyxProperties.DatabaseProperties.class)).orElse(Map.of()).isEmpty();
        if (r2dbcConfigured) {
            IgnoredSpringProperties.warnIfSet(environment, "spring.r2dbc", HalyxR2dbcDatabases.GROUP
                    + " defines the ConnectionFactory beans, so Spring Boot does not create its own");
        }
        if (ClassUtils.isPresent("dev.halyx.r2dbc.R2dbcDatabases", classLoader)) {
            R2dbcBeanRegistration.register(binder, registry, names, parameterStores);
        } else if (r2dbcConfigured) {
            throw new IllegalStateException(HalyxR2dbcDatabases.GROUP + " is configured, but halyx-r2dbc is not on the "
                    + "classpath: add the dev.halyx:halyx-r2dbc dependency and an R2DBC driver");
        }
        if (environment.containsProperty("halyx.spring-kafka.cluster")
                && !ClassUtils.isPresent("org.springframework.kafka.core.DefaultKafkaProducerFactory", classLoader)) {
            throw new IllegalStateException("halyx.spring-kafka.cluster is set, but Spring for Apache Kafka is not on "
                    + "the classpath: add org.springframework.kafka:spring-kafka (spring-boot-starter-kafka on Boot 4)");
        }
        binder.bind("halyx.document-stores", Bindable.mapOf(String.class, HalyxProperties.DocumentStoreProperties.class))
                .orElse(Map.of())
                .forEach((name, store) -> {
                    RootBeanDefinition definition = serviceBean(DocumentStore.class, "documentStore",
                            "halyx.document-stores." + name, store.provider());
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, name);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(3, store);
                    definition.setDescription("Halyx document store " + store.toTableConfig(name).name());
                    names.register(name, "halyx.document-stores." + name, "table", definition);
                });
        binder.bind("halyx.topics", Bindable.mapOf(String.class, HalyxProperties.TopicProperties.class))
                .orElse(Map.of())
                .forEach((name, topic) -> names.register(name, "halyx.topics." + name, "name",
                        messagingBean(Publisher.class, "halyx.topics." + name, name, topic.provider(), topic,
                                topic.claimCheck(), "publisher", stores.keySet())));
        binder.bind("halyx.event-buses", Bindable.mapOf(String.class, HalyxProperties.EventBusProperties.class))
                .orElse(Map.of())
                .forEach((name, bus) -> {
                    RootBeanDefinition definition = serviceBean(EventPublisher.class, "eventPublisher",
                            "halyx.event-buses." + name, bus.provider());
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, name);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(3, bus);
                    definition.setDescription("Halyx event bus " + name);
                    names.register(name, "halyx.event-buses." + name, "name", definition);
                });
        binder.bind("halyx.functions", Bindable.mapOf(String.class, HalyxProperties.FunctionProperties.class))
                .orElse(Map.of())
                .forEach((name, function) -> {
                    RootBeanDefinition definition = serviceBean(FunctionInvoker.class, "functionInvoker",
                            "halyx.functions." + name, function.provider());
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, name);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(3, function);
                    definition.setDescription("Halyx function " + name);
                    names.register(name, "halyx.functions." + name, "name", definition);
                });
        binder.bind("halyx.subscriptions", Bindable.mapOf(String.class, HalyxProperties.SubscriptionProperties.class))
                .orElse(Map.of())
                .forEach((name, subscription) -> names.register(name, "halyx.subscriptions." + name, "name",
                        messagingBean(Subscription.class, "halyx.subscriptions." + name, name, subscription.provider(),
                                subscription, subscription.claimCheck(), "subscription", stores.keySet())));
        Map<String, HalyxProperties.StreamClusterProperties> clusters = binder
                .bind("halyx.stream-clusters", Bindable.mapOf(String.class, HalyxProperties.StreamClusterProperties.class))
                .orElse(Map.of());
        binder.bind("halyx.stream-producers", Bindable.mapOf(String.class, HalyxProperties.StreamProducerProperties.class))
                .orElse(Map.of())
                .forEach((name, producer) -> names.register(name, "halyx.stream-producers." + name, "topic",
                        streamBean(StreamProducer.class, "halyx.stream-producers." + name, name, producer.cluster(),
                                producer, producer.claimCheck(), "streamProducer", clusters, stores.keySet())));
        binder.bind("halyx.stream-consumers", Bindable.mapOf(String.class, HalyxProperties.StreamConsumerProperties.class))
                .orElse(Map.of())
                .forEach((name, consumer) -> {
                    RootBeanDefinition definition = streamBean(StreamConsumer.class, "halyx.stream-consumers." + name,
                            name, consumer.cluster(), consumer, consumer.claimCheck(), "streamConsumer", clusters,
                            stores.keySet());
                    // Closing leaves the consumer group, handing its partitions to the other members.
                    definition.setDestroyMethodName(AbstractBeanDefinition.INFER_METHOD);
                    names.register(name, "halyx.stream-consumers." + name, "topic and .group", definition);
                });
        stores.forEach((name, store) -> {
            String bucket = store.bucket() == null || store.bucket().isBlank() ? name : store.bucket();
            RootBeanDefinition definition = serviceBean(BlobStore.class, "blobStore", "halyx.blob-stores." + name,
                    store.provider());
            definition.getConstructorArgumentValues().addIndexedArgumentValue(2, bucket);
            definition.setDescription("Halyx blob store " + bucket);
            names.register(name, "halyx.blob-stores." + name, "bucket", definition);
        });
    }

    /**
     * A bean made by {@code factoryMethod} of {@link HalyxBlobStores}, whose first arguments are the configuring
     * property and its provider (null when omitted), resolved when the bean is created.
     */
    static RootBeanDefinition serviceBean(Class<?> type, String factoryMethod, String property, String provider) {
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.setFactoryBeanName(FACTORY_BEAN);
        definition.setFactoryMethodName(factoryMethod);
        definition.setTargetType(type);
        ConstructorArgumentValues args = definition.getConstructorArgumentValues();
        args.addIndexedArgumentValue(0, property);
        args.addIndexedArgumentValue(1, new ConstructorArgumentValues.ValueHolder(provider, String.class.getName()));
        return definition;
    }

    /** A {@code Cache} whose value type follows its codec: strings, bytes, objects, or a {@code Codec} bean's type. */
    private static RootBeanDefinition cacheBean(String name, HalyxProperties.CacheProperties cache) {
        String property = "halyx.caches." + name;
        String codec = cache.codec() == null || cache.codec().isBlank() ? "string" : cache.codec();
        RootBeanDefinition definition = serviceBean(Cache.class, "cache", property, cache.provider());
        var args = definition.getConstructorArgumentValues();
        args.addIndexedArgumentValue(2, cache.toCacheConfig(name));
        switch (codec) {
            case "string" -> definition.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, String.class));
            case "bytes" -> {
                definition.setFactoryMethodName("bytesCache");
                definition.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, byte[].class));
            }
            case "jdk", "json" -> {
                definition.setFactoryMethodName("objectCache");
                args.addIndexedArgumentValue(3, codec);
                args.addIndexedArgumentValue(4, cache.trustedPackages());
                definition.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, Object.class));
            }
            default -> {
                // The name of a Codec bean: the value type is the codec's, known once the bean exists.
                definition.setFactoryMethodName("cacheWithCodec");
                args.addIndexedArgumentValue(3, new RuntimeBeanReference(codec));
                definition.setTargetType(Cache.class);
                definition.setAttribute(CacheCodecTypes.CODEC_BEAN, codec);
            }
        }
        definition.setDescription("Halyx cache " + cache.endpoint() + " (" + codec + ")");
        return definition;
    }

    /** A Publisher or Subscription bean; with a claim check, its blob store bean is injected too. */
    private static RootBeanDefinition messagingBean(Class<?> type, String property, String name, String provider,
                                                    Object properties, HalyxProperties.ClaimCheckProperties claimCheck,
                                                    String factoryMethod, Set<String> blobStores) {
        RootBeanDefinition definition = serviceBean(type, factoryMethod, property, provider);
        var args = definition.getConstructorArgumentValues();
        args.addIndexedArgumentValue(2, name);
        args.addIndexedArgumentValue(3, properties);
        if (claimCheck != null) {
            if (claimCheck.blobStore() == null || !blobStores.contains(claimCheck.blobStore())) {
                throw new IllegalStateException(property + ".claim-check.blob-store must name one of halyx.blob-stores "
                        + blobStores);
            }
            definition.setFactoryMethodName(factoryMethod + "WithClaimCheck");
            args.addIndexedArgumentValue(4, new RuntimeBeanReference(claimCheck.blobStore()));
        }
        definition.setDescription("Halyx " + factoryMethod + " " + name);
        return definition;
    }

    /** A StreamProducer or StreamConsumer bean on its cluster; with a claim check, its blob store bean is injected too. */
    private static RootBeanDefinition streamBean(Class<?> type, String property, String name, String cluster,
                                                 Object properties, HalyxProperties.ClaimCheckProperties claimCheck,
                                                 String factoryMethod,
                                                 Map<String, HalyxProperties.StreamClusterProperties> clusters,
                                                 Set<String> blobStores) {
        String clusterName = resolveCluster(property, cluster, clusters);
        HalyxProperties.StreamClusterProperties clusterProperties = clusters.get(clusterName);
        StreamConfig streamConfig = clusterProperties.toStreamConfig(clusterName);
        RootBeanDefinition definition = serviceBean(type, factoryMethod, "halyx.stream-clusters." + clusterName,
                clusterProperties.provider());
        var args = definition.getConstructorArgumentValues();
        args.addIndexedArgumentValue(2, name);
        args.addIndexedArgumentValue(3, properties);
        args.addIndexedArgumentValue(4, streamConfig);
        if (claimCheck != null) {
            if (claimCheck.blobStore() == null || !blobStores.contains(claimCheck.blobStore())) {
                throw new IllegalStateException(property + ".claim-check.blob-store must name one of halyx.blob-stores "
                        + blobStores);
            }
            definition.setFactoryMethodName(factoryMethod + "WithClaimCheck");
            args.addIndexedArgumentValue(5, new RuntimeBeanReference(claimCheck.blobStore()));
        }
        definition.setDescription("Halyx " + factoryMethod + " " + clusterName + "/" + name);
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
}
