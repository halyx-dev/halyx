package org.kamranzafar.cirrus.azure;

import com.azure.core.exception.HttpResponseException;
import com.azure.storage.blob.models.BlobStorageException;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.AlreadyExistsException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/** Translates Azure SDK exceptions to the portable error model. Shared by all Azure service drivers. */
public final class AzureErrors {

    private AzureErrors() {
    }

    public static Optional<CloudException> translate(Throwable error) {
        String provider = AzureProvider.ID;
        if (error instanceof HttpResponseException e && e.getResponse() != null) {
            String code = e instanceof BlobStorageException b && b.getErrorCode() != null ? b.getErrorCode().toString() : null;
            String message = e.getMessage();
            CloudException mapped = switch (e.getResponse().getStatusCode()) {
                case 404 -> new NotFoundException(message, e, provider, code);
                case 412 -> new PreconditionFailedException(message, e, provider, code);
                case 409 -> new AlreadyExistsException(message, e, provider, code);
                case 401, 403 -> new AccessDeniedException(message, e, provider, code);
                case 400, 416 -> new InvalidArgumentException(message, e, provider, code);
                case 501 -> new UnsupportedFeatureException(message, e, provider, code);
                // Azure Storage signals throttling with 503 ServerBusy as well as 429.
                case 429, 503 -> new ThrottledException(message, e, provider, code);
                default -> e.getResponse().getStatusCode() >= 500 ? new UnavailableException(message, e, provider, code) : null;
            };
            return Optional.ofNullable(mapped);
        }
        if (error instanceof UncheckedIOException || error.getCause() instanceof IOException) {
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }
}
