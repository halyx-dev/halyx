package org.kamranzafar.cirrus.parameters;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Portable parameter names: segments of letters, digits and single inner dashes, joined by {@code /},
 * such as {@code orders/db/password}. This is the intersection every backend can store: providers
 * translate the separator (Key Vault, for one, allows only letters, digits and dashes).
 */
public final class ParameterName {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9]+(-[A-Za-z0-9]+)*");

    private ParameterName() {
    }

    /** Validates a name (or prefix) and returns it. */
    public static String validate(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Parameter name must not be empty");
        }
        for (String segment : name.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid parameter name '" + name + "': use segments of letters, "
                        + "digits and single dashes separated by '/', such as orders/db/password");
            }
        }
        return name;
    }

    /** Joins a store prefix and a relative name. */
    public static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }
}
