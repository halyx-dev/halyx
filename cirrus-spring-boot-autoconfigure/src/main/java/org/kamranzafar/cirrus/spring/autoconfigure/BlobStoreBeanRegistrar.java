package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.messaging.Publisher;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.storage.BlobStore;
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

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;

/**
 * Registers one {@link BlobStore} bean per {@code cirrus.blob-stores.<name>} entry, one
 * {@code Cache<String>} bean per {@code cirrus.caches.<name>} entry and one {@code DataSource} bean per
 * {@code cirrus.databases.<name>} entry, each named {@code <name>}, so
 * they can be injected by type or with {@code @Qualifier("<name>")}. Configuration mistakes fail at
 * startup with the offending property named.
 */
class BlobStoreBeanRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    static final String FACTORY_BEAN = "cirrusBlobStores";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Binder binder = Binder.get(environment);
        Map<String, CirrusProperties.BlobStoreProperties> stores = binder
                .bind("cirrus.blob-stores", Bindable.mapOf(String.class, CirrusProperties.BlobStoreProperties.class))
                .orElse(Map.of());
        Map<String, CirrusProperties.Provider> providers = binder
                .bind("cirrus.providers", Bindable.mapOf(String.class, CirrusProperties.Provider.class))
                .orElse(Map.of());
        Map<String, CirrusProperties.CacheProperties> caches = binder
                .bind("cirrus.caches", Bindable.mapOf(String.class, CirrusProperties.CacheProperties.class))
                .orElse(Map.of());
        caches.forEach((name, cache) -> {
            String provider = resolveProvider("cirrus.caches." + name, cache.provider(), providers);
            RootBeanDefinition definition = new RootBeanDefinition(Cache.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            definition.setFactoryMethodName("cache");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, cache.toCacheConfig(name));
            definition.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, String.class));
            definition.setDescription("Cirrus cache " + provider + " " + cache.endpoint());
            registry.registerBeanDefinition(name, definition);
        });
        binder.bind("cirrus.parameter-stores", Bindable.mapOf(String.class, CirrusProperties.ParameterStoreProperties.class))
                .orElse(Map.of())
                .forEach((name, store) -> {
                    String provider = resolveProvider("cirrus.parameter-stores." + name, store.provider(), providers);
                    RootBeanDefinition definition = new RootBeanDefinition(ParameterStore.class);
                    definition.setFactoryBeanName(FACTORY_BEAN);
                    definition.setFactoryMethodName("parameterStore");
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(1, store.toStoreConfig());
                    definition.setTargetType(ParameterStore.class);
                    definition.setDescription("Cirrus " + store.kind().name().toLowerCase() + " store " + provider);
                    registry.registerBeanDefinition(name, definition);
                });
        Map<String, CirrusProperties.DatabaseProperties> databases = binder
                .bind("cirrus.databases", Bindable.mapOf(String.class, CirrusProperties.DatabaseProperties.class))
                .orElse(Map.of());
        Set<String> parameterStores = binder
                .bind("cirrus.parameter-stores", Bindable.mapOf(String.class, CirrusProperties.ParameterStoreProperties.class))
                .orElse(Map.of()).keySet();
        databases.forEach((name, database) -> {
            String property = "cirrus.databases." + name;
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
                    throw new IllegalStateException(property + ".auth.secret.store must name one of cirrus.parameter-stores "
                            + parameterStores);
                }
                definition.setFactoryMethodName("dataSourceWithSecret");
                args.addIndexedArgumentValue(3, new RuntimeBeanReference(secret.store()));
            } else {
                definition.setFactoryMethodName("dataSource");
            }
            definition.setTargetType(DataSource.class);
            definition.setPrimary(databases.size() == 1 || database.primary());
            definition.setDescription("Cirrus " + database.engine() + " database " + provider + "/" + database.database());
            registry.registerBeanDefinition(name, definition);
        });
        binder.bind("cirrus.topics", Bindable.mapOf(String.class, CirrusProperties.TopicProperties.class))
                .orElse(Map.of())
                .forEach((name, topic) -> registry.registerBeanDefinition(name, messagingBean(Publisher.class,
                        "cirrus.topics." + name, name, topic.provider(), topic, topic.claimCheck(), "publisher",
                        providers, stores.keySet())));
        binder.bind("cirrus.subscriptions", Bindable.mapOf(String.class, CirrusProperties.SubscriptionProperties.class))
                .orElse(Map.of())
                .forEach((name, subscription) -> registry.registerBeanDefinition(name, messagingBean(Subscription.class,
                        "cirrus.subscriptions." + name, name, subscription.provider(), subscription,
                        subscription.claimCheck(), "subscription", providers, stores.keySet())));
        stores.forEach((name, store) -> {
            String provider = resolveProvider("cirrus.blob-stores." + name, store.provider(), providers);
            String bucket = store.bucket() == null || store.bucket().isBlank() ? name : store.bucket();
            RootBeanDefinition definition = new RootBeanDefinition(BlobStore.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            definition.setFactoryMethodName("blobStore");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, bucket);
            definition.setTargetType(BlobStore.class);
            definition.setDescription("Cirrus blob store " + provider + "/" + bucket);
            registry.registerBeanDefinition(name, definition);
        });
    }

    /** A Publisher or Subscription bean; with a claim check, its blob store bean is injected too. */
    private static RootBeanDefinition messagingBean(Class<?> type, String property, String name, String provider,
                                                    Object properties, CirrusProperties.ClaimCheckProperties claimCheck,
                                                    String factoryMethod, Map<String, CirrusProperties.Provider> providers,
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
                throw new IllegalStateException(property + ".claim-check.blob-store must name one of cirrus.blob-stores "
                        + blobStores);
            }
            definition.setFactoryMethodName(factoryMethod + "WithClaimCheck");
            args.addIndexedArgumentValue(3, new RuntimeBeanReference(claimCheck.blobStore()));
        } else {
            definition.setFactoryMethodName(factoryMethod);
        }
        definition.setTargetType(type);
        definition.setDescription("Cirrus " + factoryMethod + " " + resolved + "/" + name);
        return definition;
    }

    static String resolveProvider(String property, String provider,
                                          Map<String, CirrusProperties.Provider> providers) {
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
