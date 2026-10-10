package org.kamranzafar.stratum.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A presigned URL plus everything a client needs to use it. Hand the whole object to the client,
 * not just the URI: providers differ in which request headers are mandatory (Azure needs
 * {@code x-ms-blob-type} on uploads; S3 and GCS reject uploads whose {@code Content-Type} differs
 * from the signed one).
 *
 * @param method    the HTTP method the URL is signed for
 * @param uri       the URL itself
 * @param expiresAt when the URL stops working
 * @param headers   request headers the client must send with the request, exactly as given
 */
public record PresignedUrl(PresignRequest.Method method, URI uri, Instant expiresAt, Map<String, String> headers) {

    public PresignedUrl {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(expiresAt, "expiresAt");
        headers = Map.copyOf(headers);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
