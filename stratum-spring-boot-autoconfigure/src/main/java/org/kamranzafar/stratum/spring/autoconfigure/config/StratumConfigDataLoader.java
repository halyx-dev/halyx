package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.interceptor.RetryInterceptor;
import org.kamranzafar.stratum.interceptor.RetryPolicy;
import org.kamranzafar.stratum.parameters.ParameterStore;
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
 * {@code StratumConfigReloader} keeps the properties up to date afterwards.
 */
public class StratumConfigDataLoader implements ConfigDataLoader<StratumConfigDataResource> {

    @Override
    public ConfigData load(ConfigDataLoaderContext context, StratumConfigDataResource resource) {
        try (Stratum stratum = Stratum.builder()
                .classLoader(StratumConfigDataLoader.class.getClassLoader())
                .register(resource.providerName(), resource.providerConfig())
                .interceptor(new RetryInterceptor(new RetryPolicy(resource.startupAttempts(), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0)))
                .build()) {
            ParameterStore store = stratum.provider(resource.providerName()).parameterStore(resource.storeConfig());
            return new ConfigData(List.of(new StratumPropertySource(resource, StratumPropertySource.load(store, resource))));
        }
    }
}
