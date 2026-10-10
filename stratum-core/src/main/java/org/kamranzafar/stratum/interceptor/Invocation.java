package org.kamranzafar.stratum.interceptor;

/** The next step in an interceptor chain: either another interceptor or the provider call itself. */
@FunctionalInterface
public interface Invocation<T> {

    T proceed();
}
