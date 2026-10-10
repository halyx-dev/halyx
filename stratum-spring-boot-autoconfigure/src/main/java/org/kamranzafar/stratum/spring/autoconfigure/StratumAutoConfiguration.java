package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.interceptor.Interceptor;
import org.kamranzafar.stratum.interceptor.RetryInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.kamranzafar.stratum.spring.autoconfigure.config.StratumConfigReloader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.annotation.Import;

/**
 * Creates the {@link Stratum} bean from {@code stratum.providers}, and a {@code BlobStore} bean for
 * each {@code stratum.blob-stores} entry.
 *
 * <p>Interceptors are applied in this order, outermost first: observations, then {@link Interceptor}
 * beans by {@code @Order}, then retries (so each retry attempt is not observed separately).
 */
@AutoConfiguration
@ConditionalOnClass(Stratum.class)
@EnableConfigurationProperties(StratumProperties.class)
@Import(BlobStoreBeanRegistrar.class)
public class StratumAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Stratum stratum(StratumProperties properties, ObjectProvider<Interceptor> interceptors,
                           ObjectProvider<StratumBuilderCustomizer> customizers) {
        Stratum.Builder builder = Stratum.builder().classLoader(Stratum.class.getClassLoader());
        properties.providers().forEach((name, provider) -> builder.register(name, provider.toProviderConfig(name)));
        interceptors.orderedStream().forEach(builder::interceptor);
        if (properties.retry().enabled()) {
            builder.interceptor(new RetryInterceptor(properties.retry().toPolicy()));
        }
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /** Keeps reloadable {@code stratum:} config imports up to date; idle when there are none. */
    @Bean
    @ConditionalOnMissingBean
    public StratumConfigReloader stratumConfigReloader(ConfigurableEnvironment environment, Stratum stratum,
                                                       ApplicationContext context) {
        return new StratumConfigReloader(environment, stratum, context);
    }

    @Bean(BlobStoreBeanRegistrar.FACTORY_BEAN)
    @ConditionalOnMissingBean
    public StratumBlobStores stratumBlobStores(Stratum stratum) {
        return new StratumBlobStores(stratum);
    }
}
