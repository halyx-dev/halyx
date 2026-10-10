package org.kamranzafar.stratum.gcp;

import com.google.cloud.BaseServiceException;
import org.kamranzafar.stratum.error.AccessDeniedException;
import org.kamranzafar.stratum.error.AlreadyExistsException;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.InvalidArgumentException;
import org.kamranzafar.stratum.error.NotFoundException;
import org.kamranzafar.stratum.error.PreconditionFailedException;
import org.kamranzafar.stratum.error.ThrottledException;
import org.kamranzafar.stratum.error.UnavailableException;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;

import java.io.UncheckedIOException;
import java.util.Optional;

/** Translates Google Cloud exceptions to the portable error model. Shared by all GCP service drivers. */
public final class GcpErrors {

    private GcpErrors() {
    }

    public static Optional<CloudException> translate(Throwable error) {
        String provider = GcpProvider.ID;
        if (error instanceof BaseServiceException e) {
            String reason = e.getReason();
            String message = e.getMessage();
            CloudException mapped = switch (e.getCode()) {
                case 404 -> new NotFoundException(message, e, provider, reason);
                case 412 -> new PreconditionFailedException(message, e, provider, reason);
                case 409 -> new AlreadyExistsException(message, e, provider, reason);
                case 401, 403 -> new AccessDeniedException(message, e, provider, reason);
                case 400, 416 -> new InvalidArgumentException(message, e, provider, reason);
                case 429 -> new ThrottledException(message, e, provider, reason);
                case 501 -> new UnsupportedFeatureException(message, e, provider, reason);
                // Code 0 means the request never got an HTTP response (network failure).
                case 0 -> new UnavailableException(message, e, provider, reason);
                default -> e.getCode() >= 500 ? new UnavailableException(message, e, provider, reason) : null;
            };
            return Optional.ofNullable(mapped);
        }
        if (error instanceof UncheckedIOException) {
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }
}
