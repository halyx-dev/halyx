package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a parameter store into the Spring {@code Environment} at startup: every value becomes a
 * property whose key is its name with {@code /} replaced by {@code .} ({@code db/pool/size} becomes
 * {@code db.pool.size}). Values are read once; restart (or use a {@code ParameterStore} bean) to see changes.
 */
public class StratumConfigDataLoader implements ConfigDataLoader<StratumConfigDataResource> {

    @Override
    public ConfigData load(ConfigDataLoaderContext context, StratumConfigDataResource resource) {
        try (Stratum stratum = Stratum.builder()
                .classLoader(StratumConfigDataLoader.class.getClassLoader())
                .register(resource.providerName(), resource.providerConfig())
                .build()) {
            ParameterStore store = stratum.provider(resource.providerName()).parameterStore(resource.storeConfig());
            Map<String, Object> properties = new LinkedHashMap<>();
            store.getAll(resource.subPrefix()).forEach((name, parameter) -> properties.put(name.replace('/', '.'), parameter.value()));
            return new ConfigData(List.of(new MapPropertySource(resource.toString(), properties)));
        }
    }
}
