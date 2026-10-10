package org.kamranzafar.cirrus.interceptor;

/**
 * Describes the operation an {@link Interceptor} is wrapping.
 *
 * @param service    the service family, such as {@code "storage"} or {@code "cache"}
 * @param operation  the operation name, such as {@code "put"} or {@code "get"}
 * @param provider   the provider id, such as {@code "aws"}
 * @param resource   the resource addressed, such as {@code "bucket/key"}
 * @param idempotent whether the operation can be safely replayed. Retry interceptors must respect
 *                   this; for example, a put from a one-shot {@code InputStream} is not replayable.
 */
public record OperationContext(String service, String operation, String provider, String resource,
                               boolean idempotent) {
}
