package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.interceptor.RetryInterceptor;
import org.kamranzafar.cirrus.interceptor.RetryPolicy;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;

import java.time.Duration;
import java.util.List;

/**
 * Loads a parameter store into the Spring {@code Environment} at startup: every value becomes a
 * property whose key is its name with {@code /} replaced by {@code .} ({@code db/pool/size} becomes
 * {@code db.pool.size}), and JSON object values also add their fields ({@code db.creds.password}).
 * A temporarily unavailable backend is retried with backoff. With a {@code reload-interval}, the
 * {@code CirrusConfigReloader} keeps the properties up to date afterwards.
 */
public class CirrusConfigDataLoader implements ConfigDataLoader<CirrusConfigDataResource> {

    @Override
    public ConfigData load(ConfigDataLoaderContext context, CirrusConfigDataResource resource) {
        try (Cirrus cirrus = Cirrus.builder()
                .classLoader(CirrusConfigDataLoader.class.getClassLoader())
                .register(resource.providerName(), resource.providerConfig())
                .interceptor(new RetryInterceptor(new RetryPolicy(resource.startupAttempts(), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0)))
                .build()) {
            ParameterStore store = cirrus.provider(resource.providerName()).parameterStore(resource.storeConfig());
            return new ConfigData(List.of(new CirrusPropertySource(resource, CirrusPropertySource.load(store, resource))));
        }
    }
}
