package org.kamranzafar.stratum.spring.autoconfigure.cache;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.Codec;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.kamranzafar.stratum.spring.autoconfigure.StratumProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * A {@link StratumCacheManager} for Spring's caching annotations, when {@code stratum.cache-manager.endpoint}
 * is set and the application defines no other {@link CacheManager}. Runs before Boot's own cache
 * auto-configuration, which then backs off. Applications still need {@code @EnableCaching}.
 */
@AutoConfiguration(after = StratumAutoConfiguration.class, beforeName = {
        "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration", // Boot 3
        "org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration" // Boot 4
})
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "stratum.cache-manager", name = "endpoint")
@Import({StratumCacheManagerAutoConfiguration.Jackson3Codec.class, StratumCacheManagerAutoConfiguration.Jackson2Codec.class,
        StratumCacheManagerAutoConfiguration.JdkCodec.class})
public class StratumCacheManagerAutoConfiguration {

    static final String CODEC_BEAN = "stratumCacheCodec";

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public StratumCacheManager cacheManager(Stratum stratum, StratumProperties properties,
                                            ObjectProvider<Codec<Object>> codec) {
        var manager = properties.cacheManager();
        String provider = manager.provider();
        if (provider == null) {
            if (properties.providers().size() != 1) {
                throw new IllegalStateException("stratum.cache-manager.provider is required when "
                        + properties.providers().size() + " providers are configured " + properties.providers().keySet());
            }
            provider = properties.providers().keySet().iterator().next();
        }
        Codec<Object> valueCodec = codec.getIfAvailable(() -> {
            throw new IllegalStateException("stratum.cache-manager.codec=json needs Jackson (3 on Boot 4, 2 on Boot 3) "
                    + "on the classpath");
        });
        return new StratumCacheManager(stratum, provider, manager, valueCodec);
    }

    private static void requireTrustedPackages(StratumProperties properties) {
        if (properties.cacheManager().trustedPackages().isEmpty()) {
            throw new IllegalStateException("stratum.cache-manager.trusted-packages is required with codec=json: "
                    + "list the packages of your cached types, such as com.example.orders");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "tools.jackson.databind.ObjectMapper")
    @ConditionalOnProperty(prefix = "stratum.cache-manager", name = "codec", havingValue = "json")
    static class Jackson3Codec {
        @Bean(CODEC_BEAN)
        @ConditionalOnMissingBean(name = CODEC_BEAN)
        Codec<Object> stratumCacheCodec(StratumProperties properties,
                                        ObjectProvider<tools.jackson.databind.ObjectMapper> mapper) {
            requireTrustedPackages(properties);
            return new Jackson3JsonCodec(mapper.getIfAvailable(tools.jackson.databind.json.JsonMapper::new),
                    properties.cacheManager().trustedPackages());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    @ConditionalOnProperty(prefix = "stratum.cache-manager", name = "codec", havingValue = "json")
    static class Jackson2Codec {
        @Bean(CODEC_BEAN)
        @ConditionalOnMissingBean(name = CODEC_BEAN)
        Codec<Object> stratumCacheCodec(StratumProperties properties,
                                        ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper> mapper) {
            requireTrustedPackages(properties);
            return new Jackson2JsonCodec(mapper.getIfAvailable(com.fasterxml.jackson.databind.ObjectMapper::new),
                    properties.cacheManager().trustedPackages());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "stratum.cache-manager", name = "codec", havingValue = "jdk", matchIfMissing = true)
    static class JdkCodec {
        @Bean(CODEC_BEAN)
        @ConditionalOnMissingBean(name = CODEC_BEAN)
        Codec<Object> stratumCacheCodec(StratumProperties properties) {
            return new JdkSerializationCodec(properties.cacheManager().trustedPackages());
        }
    }
}
