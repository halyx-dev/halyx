package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.Cirrus;

/**
 * Customises the auto-configured {@link Cirrus} before it is built, for example to register a
 * provider plugin directly or add registrations computed at runtime.
 */
@FunctionalInterface
public interface CirrusBuilderCustomizer {

    void customize(Cirrus.Builder builder);
}
