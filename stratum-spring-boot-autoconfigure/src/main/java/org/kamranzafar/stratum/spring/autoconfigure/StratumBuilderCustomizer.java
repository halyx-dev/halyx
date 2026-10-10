package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.Stratum;

/**
 * Customises the auto-configured {@link Stratum} before it is built, for example to register a
 * provider plugin directly or add registrations computed at runtime.
 */
@FunctionalInterface
public interface StratumBuilderCustomizer {

    void customize(Stratum.Builder builder);
}
