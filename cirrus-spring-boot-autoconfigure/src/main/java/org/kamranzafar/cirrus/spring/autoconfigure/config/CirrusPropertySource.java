package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.kamranzafar.cirrus.parameters.Parameter;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Properties imported from a parameter store with {@code spring.config.import=cirrus:...}. The values
 * can be replaced atomically when the store changes (see {@code CirrusConfigReloader}).
 */
public final class CirrusPropertySource extends EnumerablePropertySource<CirrusConfigDataResource> {

    private volatile Map<String, Object> values;

    CirrusPropertySource(CirrusConfigDataResource resource, Map<String, Object> values) {
        super(resource.toString(), resource);
        this.values = Map.copyOf(values);
    }

    CirrusConfigDataResource resource() {
        return getSource();
    }

    /** Converts store values to properties: {@code /} becomes {@code .}, and JSON objects are expanded. */
    static Map<String, Object> toProperties(Map<String, Parameter> parameters, boolean expandJson) {
        Map<String, Object> properties = new LinkedHashMap<>();
        parameters.forEach((name, parameter) -> {
            String key = name.replace('/', '.');
            properties.put(key, parameter.value());
            if (expandJson) {
                parameter.fields().forEach((field, value) -> properties.put(key + "." + field, value));
            }
        });
        return properties;
    }

    static Map<String, Object> load(ParameterStore store, CirrusConfigDataResource resource) {
        return toProperties(store.getAll(resource.subPrefix()), resource.expandJson());
    }

    /** Replaces all values; returns the keys whose value was added, changed or removed. */
    Set<String> replace(Map<String, Object> next) {
        Map<String, Object> previous = values;
        Set<String> changed = new LinkedHashSet<>();
        next.forEach((key, value) -> {
            if (!value.equals(previous.get(key))) {
                changed.add(key);
            }
        });
        previous.keySet().stream().filter(key -> !next.containsKey(key)).forEach(changed::add);
        values = Map.copyOf(next);
        return changed;
    }

    @Override
    public String[] getPropertyNames() {
        return values.keySet().toArray(String[]::new);
    }

    @Override
    public Object getProperty(String name) {
        return values.get(name);
    }
}
