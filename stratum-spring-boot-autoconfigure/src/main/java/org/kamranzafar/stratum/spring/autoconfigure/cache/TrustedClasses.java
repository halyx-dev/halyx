package org.kamranzafar.stratum.spring.autoconfigure.cache;

import java.util.List;

/** Which classes cached values may deserialize to: {@code java.*} plus configured package prefixes. */
final class TrustedClasses {

    private final List<String> packages;

    TrustedClasses(List<String> packages) {
        this.packages = packages.stream().map(p -> p.endsWith(".") ? p : p + ".").toList();
    }

    boolean isRestricted() {
        return !packages.isEmpty();
    }

    boolean allows(String className) {
        if (className.startsWith("java.") || className.equals("org.springframework.cache.support.NullValue")) {
            return true;
        }
        return packages.stream().anyMatch(className::startsWith);
    }

    Class<?> load(String className) {
        if (!allows(className)) {
            throw new IllegalArgumentException("Cached value of type " + className
                    + " is not in stratum.cache-manager.trusted-packages " + packages);
        }
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cached value type not found: " + className, e);
        }
    }
}
