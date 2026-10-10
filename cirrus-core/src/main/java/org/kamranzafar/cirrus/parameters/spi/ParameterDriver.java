package org.kamranzafar.cirrus.parameters.spi;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.parameters.Parameter;

import java.util.Map;
import java.util.Optional;

/**
 * Provider SPI for parameter stores. Names are full portable names (store prefix applied, validated);
 * drivers translate them to the backend's naming rules and back. Returned parameters carry full names.
 */
public interface ParameterDriver {

    Optional<Parameter> get(String name);

    Optional<Parameter> get(String name, String version);

    /**
     * Every parameter whose name equals {@code prefix} or starts with {@code prefix + "/"} (all for "");
     * with native names, every parameter whose name starts with {@code prefix}.
     */
    Map<String, Parameter> list(String prefix);

    Parameter put(String name, String value);

    void delete(String name);

    Capabilities capabilities();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);
}
