package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

/**
 * Registers one {@link BlobStore} bean per {@code cirrus.blob-stores.<name>} entry and one
 * {@code Cache<String>} bean per {@code cirrus.caches.<name>} entry, each named {@code <name>}, so
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
