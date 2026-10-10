package org.kamranzafar.stratum.aws.parameters;

import org.kamranzafar.stratum.aws.AwsErrors;
import org.kamranzafar.stratum.aws.AwsProvider;
import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.parameters.Parameter;
import org.kamranzafar.stratum.parameters.ParameterCapability;
import org.kamranzafar.stratum.parameters.spi.AbstractParameterDriver;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.ParameterVersionNotFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parameters on SSM Parameter Store. Portable names map to hierarchical parameters with a leading
 * slash ({@code orders/db/url} becomes {@code /orders/db/url}). Values are written as {@code String}
 * parameters, or {@code SecureString} (KMS-encrypted) with the {@code aws.ssm.secure} store property.
 */
public final class SsmParameterDriver extends AbstractParameterDriver {

    public static final String SECURE = "aws.ssm.secure";

    private final SsmClient ssm;
    private final boolean secure;

    public SsmParameterDriver(SsmClient ssm, boolean secure) {
        super(AwsProvider.ID, Capabilities.of(ParameterCapability.VERSIONS));
        this.ssm = ssm;
        this.secure = secure;
    }

    @Override
    public Optional<Parameter> get(String name) {
        return fetch("/" + name);
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        return fetch("/" + name + ":" + version);
    }

    private Optional<Parameter> fetch(String selector) {
        try {
            var p = ssm.getParameter(b -> b.name(selector).withDecryption(true)).parameter();
            return Optional.of(toPortable(p));
        } catch (ParameterNotFoundException | ParameterVersionNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Parameter> list(String prefix) {
        Map<String, Parameter> found = new LinkedHashMap<>();
        if (!prefix.isEmpty()) {
            get(prefix).ifPresent(p -> found.put(p.name(), p)); // the prefix itself is not returned by path queries
        }
        ssm.getParametersByPathPaginator(b -> b.path("/" + prefix).recursive(true).withDecryption(true))
                .stream().flatMap(page -> page.parameters().stream()).forEach(p -> {
                    Parameter portable = toPortable(p);
                    if (under(portable.name(), prefix)) {
                        found.put(portable.name(), portable);
                    }
                });
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        var response = ssm.putParameter(b -> b.name("/" + name).value(value).overwrite(true)
                .type(secure ? ParameterType.SECURE_STRING : ParameterType.STRING));
        return new Parameter(name, value, String.valueOf(response.version()), null);
    }

    @Override
    public void delete(String name) {
        try {
            ssm.deleteParameter(b -> b.name("/" + name));
        } catch (ParameterNotFoundException ignored) {
            // already gone
        }
    }

    private static Parameter toPortable(software.amazon.awssdk.services.ssm.model.Parameter p) {
        String name = p.name().startsWith("/") ? p.name().substring(1) : p.name();
        return new Parameter(name, p.value(), String.valueOf(p.version()), p.lastModifiedDate());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(ssm);
    }
}
