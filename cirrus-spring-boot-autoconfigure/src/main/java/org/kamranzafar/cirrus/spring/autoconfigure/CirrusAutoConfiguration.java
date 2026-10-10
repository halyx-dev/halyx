package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.interceptor.Interceptor;
import org.kamranzafar.cirrus.interceptor.RetryInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.kamranzafar.cirrus.spring.autoconfigure.config.CirrusConfigReloader;
import org.kamranzafar.cirrus.spring.messaging.CirrusListenerRegistrar;
import org.kamranzafar.cirrus.spring.messaging.MessageListenerDecorator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.annotation.Import;

/**
 * Creates the {@link Cirrus} bean from {@code cirrus.providers}, and a {@code BlobStore} bean for
 * each {@code cirrus.blob-stores} entry.
 *
 * <p>Interceptors are applied in this order, outermost first: observations, then {@link Interceptor}
 * beans by {@code @Order}, then retries (so each retry attempt is not observed separately).
 */
// Before Boot's DataSource auto-configuration (Boot 3 and Boot 4 packages), which backs off when the
// cirrus.databases DataSource beans exist; JPA, Spring Data, JdbcTemplate and Flyway then use ours.
@AutoConfiguration(beforeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ConditionalOnClass(Cirrus.class)
@EnableConfigurationProperties(CirrusProperties.class)
@Import(BlobStoreBeanRegistrar.class)
public class CirrusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Cirrus cirrus(CirrusProperties properties, ObjectProvider<Interceptor> interceptors,
                           ObjectProvider<CirrusBuilderCustomizer> customizers) {
        Cirrus.Builder builder = Cirrus.builder().classLoader(Cirrus.class.getClassLoader());
        properties.providers().forEach((name, provider) -> builder.register(name, provider.toProviderConfig(name)));
        interceptors.orderedStream().forEach(builder::interceptor);
        if (properties.retry().enabled()) {
            builder.interceptor(new RetryInterceptor(properties.retry().toPolicy()));
        }
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /** Keeps reloadable {@code cirrus:} config imports up to date; idle when there are none. */
    @Bean
    @ConditionalOnMissingBean
    public CirrusConfigReloader cirrusConfigReloader(ConfigurableEnvironment environment, Cirrus cirrus,
                                                       ApplicationContext context) {
        return new CirrusConfigReloader(environment, cirrus, context);
    }

    /** Runs {@code @CirrusListener} methods; static, as a post-processor must be. */
    @Bean
    @ConditionalOnMissingBean
    public static CirrusListenerRegistrar cirrusListenerRegistrar(ObjectProvider<CirrusProperties> properties,
                                                                  ObjectProvider<MessageListenerDecorator> decorators) {
        return new CirrusListenerRegistrar(properties, decorators);
    }

    @Bean(BlobStoreBeanRegistrar.FACTORY_BEAN)
    @ConditionalOnMissingBean
    public CirrusBlobStores cirrusBlobStores(Cirrus cirrus) {
        return new CirrusBlobStores(cirrus);
    }
}
