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

import java.util.ArrayList;
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

    /** GetParameters accepts up to 10 names. */
    private static final int BATCH = 10;

    public SsmParameterDriver(SsmClient ssm, boolean secure, boolean nativeNames) {
        super(AwsProvider.ID, Capabilities.of(ParameterCapability.VERSIONS), nativeNames);
        this.ssm = ssm;
        this.secure = secure;
    }

    /** Portable names gain a leading slash; native names are used as they are. */
    private String ssmName(String name) {
        return nativeNames ? name : "/" + name;
    }

    private String portable(String ssmName) {
        return nativeNames || !ssmName.startsWith("/") ? ssmName : ssmName.substring(1);
    }

    @Override
    public Optional<Parameter> get(String name) {
        return fetch(ssmName(name));
    }

    @Override
    public Optional<Parameter> get(String name, String version) {
        return fetch(ssmName(name) + ":" + version);
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
        if (nativeNames) {
            return listByNamePrefix(prefix);
        }
        Map<String, Parameter> found = new LinkedHashMap<>();
        if (!prefix.isEmpty()) {
            get(prefix).ifPresent(p -> found.put(p.name(), p)); // the prefix itself is not returned by path queries
        }
        ssm.getParametersByPathPaginator(b -> b.path("/" + prefix).recursive(true).withDecryption(true))
                .stream().flatMap(page -> page.parameters().stream()).forEach(p -> {
                    Parameter portable = toPortable(p);
                    if (matches(portable.name(), prefix)) {
                        found.put(portable.name(), portable);
                    }
                });
        return found;
    }

    /** Native names need not be paths: find them by name prefix, then fetch in batches. */
    private Map<String, Parameter> listByNamePrefix(String prefix) {
        List<String> names = new ArrayList<>();
        ssm.describeParametersPaginator(b -> {
            if (!prefix.isEmpty()) {
                b.parameterFilters(f -> f.key("Name").option("BeginsWith").values(prefix));
            }
        }).stream().flatMap(page -> page.parameters().stream()).forEach(meta -> {
            if (meta.name().startsWith(prefix)) {
                names.add(meta.name());
            }
        });
        Map<String, Parameter> found = new LinkedHashMap<>();
        for (int from = 0; from < names.size(); from += BATCH) {
            List<String> batch = names.subList(from, Math.min(from + BATCH, names.size()));
            ssm.getParameters(b -> b.names(batch).withDecryption(true)).parameters()
                    .forEach(p -> found.put(p.name(), toPortable(p)));
        }
        return found;
    }

    @Override
    public Parameter put(String name, String value) {
        var response = ssm.putParameter(b -> b.name(ssmName(name)).value(value).overwrite(true)
                .type(secure ? ParameterType.SECURE_STRING : ParameterType.STRING));
        return new Parameter(name, value, String.valueOf(response.version()), null);
    }

    @Override
    public void delete(String name) {
        try {
            ssm.deleteParameter(b -> b.name(ssmName(name)));
        } catch (ParameterNotFoundException ignored) {
            // already gone
        }
    }

    private Parameter toPortable(software.amazon.awssdk.services.ssm.model.Parameter p) {
        return new Parameter(portable(p.name()), p.value(), String.valueOf(p.version()), p.lastModifiedDate());
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
