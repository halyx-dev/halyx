package org.kamranzafar.stratum.parameters;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.interceptor.InterceptorChain;
import org.kamranzafar.stratum.interceptor.OperationContext;
import org.kamranzafar.stratum.parameters.spi.ParameterDriver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The portable {@link ParameterStore}: name validation, prefixing, a read cache, interceptors and
 * exception translation around a provider {@link ParameterDriver}. Writes and deletes through this
 * store invalidate its cache.
 */
public final class DefaultParameterStore implements ParameterStore {

    private static final String SERVICE = "parameters";

    private record Cached<T>(T value, long expiresAtNanos) {
    }

    private final String provider;
    private final ParameterStoreConfig config;
    private final ParameterDriver driver;
    private final InterceptorChain interceptors;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();

    public DefaultParameterStore(String provider, ParameterStoreConfig config, ParameterDriver driver,
                                 InterceptorChain interceptors) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public ParameterKind kind() {
        return config.kind();
    }

    @Override
    public Optional<Parameter> get(String name) {
        String full = full(name);
        return cached("get:" + full, () -> execute("get", full, () -> driver.get(full)))
                .map(p -> p.rename(name));
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        String full = full(name);
        Objects.requireNonNull(version, "version");
        capabilities().require(ParameterCapability.VERSIONS, provider);
        return cached("version:" + full + "@" + version, () -> execute("getVersion", full, () -> driver.get(full, version)))
                .map(p -> p.rename(name));
    }

    @Override
    public Map<String, Parameter> getAll(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String full = prefix.isEmpty() ? config.prefix() : full(prefix);
        Map<String, Parameter> found = cached("list:" + full, () -> execute("getAll", full, () -> driver.list(full)));
        Map<String, Parameter> relative = new TreeMap<>();
        found.forEach((name, parameter) -> {
            String rel = relative(name);
            relative.put(rel, parameter.rename(rel));
        });
        return new LinkedHashMap<>(relative);
    }

    @Override
    public Parameter put(String name, String value) {
        String full = full(name);
        Objects.requireNonNull(value, "value");
        Parameter stored = execute("put", full, () -> driver.put(full, value));
        cache.clear();
        return stored.rename(name);
    }

    @Override
    public void delete(String name) {
        String full = full(name);
        execute("delete", full, () -> {
            driver.delete(full);
            return null;
        });
        cache.clear();
    }

    @Override
    public void refresh() {
        cache.clear();
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(Objects.requireNonNull(nativeType, "nativeType"));
    }

    private String full(String name) {
        return ParameterName.join(config.prefix(), ParameterName.validate(name));
    }

    private String relative(String fullName) {
        return config.prefix().isEmpty() ? fullName : fullName.substring(config.prefix().length() + 1);
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> load) {
        if (config.cacheTtl().isZero()) {
            return load.get();
        }
        long now = System.nanoTime();
        Cached<?> hit = cache.get(key);
        if (hit != null && now - hit.expiresAtNanos() < 0) {
            return (T) hit.value();
        }
        T value = load.get();
        cache.put(key, new Cached<>(value, now + config.cacheTtl().toNanos()));
        return value;
    }

    private <T> T execute(String operation, String name, Supplier<T> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider, name, !"put".equals(operation));
        return interceptors.execute(context, () -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }
}
