package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

/**
 * Registers one {@link BlobStore} bean per {@code stratum.blob-stores.<name>} entry, named
 * {@code <name>}, so stores can be injected by type or with {@code @Qualifier("<name>")}.
 * Configuration mistakes fail at startup with the offending property named.
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
        stores.forEach((name, store) -> {
            String provider = resolveProvider(name, store, providers);
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

    private static String resolveProvider(String name, StratumProperties.BlobStoreProperties store,
                                          Map<String, StratumProperties.Provider> providers) {
        if (store.provider() == null) {
            if (providers.size() == 1) {
                return providers.keySet().iterator().next();
            }
            throw new IllegalStateException("stratum.blob-stores." + name + ".provider is required when "
                    + providers.size() + " providers are configured " + providers.keySet());
        }
        if (!providers.containsKey(store.provider())) {
            throw new IllegalStateException("stratum.blob-stores." + name + ".provider refers to unknown provider '"
                    + store.provider() + "'. Configured providers: " + providers.keySet());
        }
        return store.provider();
    }
}
