package org.kamranzafar.cirrus.interceptor;

/**
 * Cross-cutting behaviour (retry, metrics, tracing, logging) applied around every operation of every
 * portable client, on every provider. Implementations call {@code next.proceed()} to continue.
 *
 * <p>Exceptions seen by an interceptor have already been translated to
 * {@link org.kamranzafar.cirrus.error.CloudException}, so interceptors are provider-neutral.
 */
public interface Interceptor {

    <T> T intercept(OperationContext context, Invocation<T> next);
}
