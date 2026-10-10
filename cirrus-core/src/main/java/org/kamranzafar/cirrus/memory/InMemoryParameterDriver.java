package org.kamranzafar.cirrus.memory;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.parameters.Parameter;
import org.kamranzafar.cirrus.parameters.ParameterCapability;
import org.kamranzafar.cirrus.parameters.spi.AbstractParameterDriver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/** In-memory parameters with numbered versions, like SSM Parameter Store. */
final class InMemoryParameterDriver extends AbstractParameterDriver {

    private final ConcurrentSkipListMap<String, List<Parameter>> values;

    InMemoryParameterDriver(ConcurrentSkipListMap<String, List<Parameter>> values, boolean nativeNames) {
        super(InMemoryProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.values = values;
    }

    @Override
    public Optional<Parameter> get(String name) {
        List<Parameter> versions = values.get(name);
        return versions == null ? Optional.empty() : Optional.of(versions.getLast());
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        List<Parameter> versions = values.get(name);
        return versions == null ? Optional.empty()
                : versions.stream().filter(p -> p.version().equals(version)).findFirst();
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        Map<String, Parameter> found = new LinkedHashMap<>();
        values.forEach((name, versions) -> {
            if (matches(name, prefix)) {
                found.put(name, versions.getLast());
            }
        });
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        List<Parameter> versions = values.compute(name, (k, existing) -> {
            List<Parameter> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.add(new Parameter(name, value, String.valueOf(next.size() + 1), Instant.now()));
            return List.copyOf(next);
        });
        return versions.getLast();
    }

    @Override
    public void delete(String name) {
        values.remove(name);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of();
    }
}
