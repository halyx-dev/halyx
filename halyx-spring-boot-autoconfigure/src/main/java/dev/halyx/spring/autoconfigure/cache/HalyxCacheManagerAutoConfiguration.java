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
package dev.halyx.spring.autoconfigure.cache;

import dev.halyx.Halyx;
import dev.halyx.cache.Codec;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.HalyxProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.Executor;

/**
 * A {@link HalyxCacheManager} for Spring's caching annotations, when {@code halyx.cache-manager.endpoint}
 * is set and the application defines no other {@link CacheManager}. Runs before Boot's own cache
 * auto-configuration, which then backs off. Applications still need {@code @EnableCaching}.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class, beforeName = {
        "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration", // Boot 3
        "org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration" // Boot 4
})
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "halyx.cache-manager", name = "endpoint")
@Import({HalyxCacheManagerAutoConfiguration.Jackson3Codec.class, HalyxCacheManagerAutoConfiguration.Jackson2Codec.class,
        HalyxCacheManagerAutoConfiguration.JdkCodec.class})
public class HalyxCacheManagerAutoConfiguration {

    static final String CODEC_BEAN = "halyxCacheCodec";

    /** Optional: an {@code Executor} bean of this name runs cache calls for async and reactive {@code @Cacheable}. */
    public static final String EXECUTOR_BEAN = "halyxCacheExecutor";

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public HalyxCacheManager cacheManager(Halyx halyx, HalyxProperties properties,
                                            ObjectProvider<Codec<Object>> codec, BeanFactory beans) {
        var manager = properties.cacheManager();
        String provider = manager.provider();
        if (provider == null) {
            if (properties.providers().size() != 1) {
                throw new IllegalStateException("halyx.cache-manager.provider is required when "
                        + properties.providers().size() + " providers are configured " + properties.providers().keySet());
            }
            provider = properties.providers().keySet().iterator().next();
        }
        Codec<Object> valueCodec = codec.getIfAvailable(() -> {
            throw new IllegalStateException("halyx.cache-manager.codec=json needs Jackson (3 on Boot 4, 2 on Boot 3) "
                    + "on the classpath");
        });
        if (beans.containsBean(EXECUTOR_BEAN)) {
            return new HalyxCacheManager(halyx, provider, manager, valueCodec, beans.getBean(EXECUTOR_BEAN, Executor.class));
        }
        return new HalyxCacheManager(halyx, provider, manager, valueCodec);
    }

    private static void requireTrustedPackages(HalyxProperties properties) {
        if (properties.cacheManager().trustedPackages().isEmpty()) {
            throw new IllegalStateException("halyx.cache-manager.trusted-packages is required with codec=json: "
                    + "list the packages of your cached types, such as com.example.orders");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "tools.jackson.databind.ObjectMapper")
    @ConditionalOnProperty(prefix = "halyx.cache-manager", name = "codec", havingValue = "json")
    static class Jackson3Codec {
        @Bean(CODEC_BEAN)
        @ConditionalOnMissingBean(name = CODEC_BEAN)
        Codec<Object> halyxCacheCodec(HalyxProperties properties,
                                        ObjectProvider<tools.jackson.databind.ObjectMapper> mapper) {
            requireTrustedPackages(properties);
            return new Jackson3JsonCodec(mapper.getIfAvailable(tools.jackson.databind.json.JsonMapper::new),
                    properties.cacheManager().trustedPackages());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    @ConditionalOnProperty(prefix = "halyx.cache-manager", name = "codec", havingValue = "json")
    static class Jackson2Codec {
        @Bean(CODEC_BEAN)
        @ConditionalOnMissingBean(name = CODEC_BEAN)
        Codec<Object> halyxCacheCodec(HalyxProperties properties,
                                        ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> mapper) {
            requireTrustedPackages(properties);
            return new Jackson2JsonCodec(mapper.getIfAvailable(com.fasterxml.jackson.databind.ObjectMapper::new),
                    properties.cacheManager().trustedPackages());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "halyx.cache-manager", name = "codec", havingValue = "jdk", matchIfMissing = true)
    static class JdkCodec {
        @Bean(CODEC_BEAN)
        @ConditionalOnMissingBean(name = CODEC_BEAN)
        Codec<Object> halyxCacheCodec(HalyxProperties properties) {
            return new JdkSerializationCodec(properties.cacheManager().trustedPackages());
        }
    }
}
