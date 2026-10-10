package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.storage.BlobStore;
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
 * Registers one {@link BlobStore} bean per {@code stratum.blob-stores.<name>} entry and one
 * {@code Cache<String>} bean per {@code stratum.caches.<name>} entry, each named {@code <name>}, so
 * they can be injected by type or with {@code @Qualifier("<name>")}. Configuration mistakes fail at
 * startup with the offending property named.
 */
class BlobStoreBeanRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    static final String FACTORY_BEAN = "stratumBlobStores";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Binder binder = Binder.get(environment);
        Map<String, StratumProperties.BlobStoreProperties> stores = binder
                .bind("stratum.blob-stores", Bindable.mapOf(String.class, StratumProperties.BlobStoreProperties.class))
                .orElse(Map.of());
        Map<String, StratumProperties.Provider> providers = binder
                .bind("stratum.providers", Bindable.mapOf(String.class, StratumProperties.Provider.class))
                .orElse(Map.of());
        Map<String, StratumProperties.CacheProperties> caches = binder
                .bind("stratum.caches", Bindable.mapOf(String.class, StratumProperties.CacheProperties.class))
                .orElse(Map.of());
        caches.forEach((name, cache) -> {
            String provider = resolveProvider("stratum.caches." + name, cache.provider(), providers);
            RootBeanDefinition definition = new RootBeanDefinition(Cache.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            definition.setFactoryMethodName("cache");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, cache.toCacheConfig(name));
            definition.setTargetType(ResolvableType.forClassWithGenerics(Cache.class, String.class));
            definition.setDescription("Stratum cache " + provider + " " + cache.endpoint());
            registry.registerBeanDefinition(name, definition);
        });
        stores.forEach((name, store) -> {
            String provider = resolveProvider("stratum.blob-stores." + name, store.provider(), providers);
            String bucket = store.bucket() == null || store.bucket().isBlank() ? name : store.bucket();
            RootBeanDefinition definition = new RootBeanDefinition(BlobStore.class);
            definition.setFactoryBeanName(FACTORY_BEAN);
            definition.setFactoryMethodName("blobStore");
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, provider);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(1, bucket);
            definition.setTargetType(BlobStore.class);
            definition.setDescription("Stratum blob store " + provider + "/" + bucket);
            registry.registerBeanDefinition(name, definition);
        });
    }

    private static String resolveProvider(String property, String provider,
                                          Map<String, StratumProperties.Provider> providers) {
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
