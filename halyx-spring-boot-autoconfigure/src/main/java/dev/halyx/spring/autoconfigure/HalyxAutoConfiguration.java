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

import dev.halyx.Halyx;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.RetryInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.halyx.spring.autoconfigure.config.HalyxConfigReloader;
import dev.halyx.spring.messaging.HalyxListenerRegistrar;
import dev.halyx.spring.messaging.MessageListenerDecorator;
import dev.halyx.spring.stream.RecordListenerDecorator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.annotation.Import;

/**
 * Creates the {@link Halyx} bean from {@code halyx.providers}, and a bean for each configured blob store,
 * cache, parameter store, database, document store, topic, subscription and stream producer and consumer.
 *
 * <p>Interceptors are applied in this order, outermost first: observations, then {@link Interceptor}
 * beans by {@code @Order}, then retries (so each retry attempt is not observed separately).
 */
// Before Boot's DataSource auto-configuration (Boot 3 and Boot 4 packages), which backs off when the
// halyx.databases DataSource beans exist; JPA, Spring Data, JdbcTemplate and Flyway then use ours.
@AutoConfiguration(beforeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ConditionalOnClass(Halyx.class)
@EnableConfigurationProperties(HalyxProperties.class)
@Import(BlobStoreBeanRegistrar.class)
public class HalyxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Halyx halyx(HalyxProperties properties, ObjectProvider<Interceptor> interceptors,
                           ObjectProvider<HalyxBuilderCustomizer> customizers) {
        Halyx.Builder builder = Halyx.builder().classLoader(Halyx.class.getClassLoader());
        properties.providers().forEach((name, provider) -> builder.register(name, provider.toProviderConfig(name)));
        interceptors.orderedStream().forEach(builder::interceptor);
        if (properties.retry().enabled()) {
            builder.interceptor(new RetryInterceptor(properties.retry().toPolicy()));
        }
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /** Keeps reloadable {@code halyx:} config imports up to date; idle when there are none. */
    @Bean
    @ConditionalOnMissingBean
    public HalyxConfigReloader halyxConfigReloader(ConfigurableEnvironment environment, Halyx halyx,
                                                       ApplicationContext context) {
        return new HalyxConfigReloader(environment, halyx, context);
    }

    /** Runs {@code @HalyxListener} methods; static, as a post-processor must be. */
    @Bean
    @ConditionalOnMissingBean
    public static HalyxListenerRegistrar halyxListenerRegistrar(ObjectProvider<HalyxProperties> properties,
                                                                  ObjectProvider<MessageListenerDecorator> decorators,
                                                                  ObjectProvider<RecordListenerDecorator> recordDecorators) {
        return new HalyxListenerRegistrar(properties, decorators, recordDecorators);
    }

    @Bean(BlobStoreBeanRegistrar.FACTORY_BEAN)
    @ConditionalOnMissingBean
    public HalyxBlobStores halyxBlobStores(Halyx halyx) {
        return new HalyxBlobStores(halyx);
    }
}
