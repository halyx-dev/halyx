/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.cirrus.aws;

import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.AlreadyExistsException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.error.UnknownCloudException;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Translates AWS SDK exceptions to the portable error model. Shared by all AWS service drivers. */
public final class AwsErrors {

    private static final Set<String> THROTTLING_CODES =
            Set.of("SlowDown", "Throttling", "ThrottlingException", "RequestLimitExceeded", "TooManyRequestsException");

    /**
     * Access failures, whatever the HTTP status: JSON-protocol services such as Secrets Manager, SSM and
     * DynamoDB report AccessDeniedException with 400, not 403.
     */
    private static final Set<String> ACCESS_DENIED_CODES = Set.of("AccessDenied", "AccessDeniedException",
            "UnauthorizedOperation", "UnrecognizedClientException", "InvalidClientTokenId", "ExpiredToken",
            "ExpiredTokenException", "SignatureDoesNotMatch", "AuthorizationError", "AuthorizationErrorException",
            "KMS.AccessDeniedException");

    /** Services that report a missing resource with 400 and a specific code. */
    private static final Set<String> NOT_FOUND_CODES =
            Set.of("ParameterNotFound", "ParameterVersionNotFound", "ResourceNotFoundException",
                    "AWS.SimpleQueueService.NonExistentQueue", "QueueDoesNotExist", "NotFound");

    private AwsErrors() {
    }

    /** Maps an error code reported without an HTTP status, such as a per-key DeleteObjects error. */
    public static CloudException fromCode(String code, String message) {
        String provider = AwsProvider.ID;
        if (code != null && THROTTLING_CODES.contains(code)) {
            return new ThrottledException(message, null, provider, code);
        }
        return switch (code == null ? "" : code) {
            case "AccessDenied" -> new AccessDeniedException(message, null, provider, code);
            case "NoSuchKey", "NoSuchBucket" -> new NotFoundException(message, null, provider, code);
            case "PreconditionFailed" -> new PreconditionFailedException(message, null, provider, code);
            case "InternalError", "ServiceUnavailable" -> new UnavailableException(message, null, provider, code);
            default -> new UnknownCloudException(message, null, provider, code);
        };
    }

    public static Optional<CloudException> translate(Throwable error) {
        String provider = AwsProvider.ID;
        if (error instanceof AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            String message = e.getMessage();
            int status = e.statusCode();
            if (status == 429 || (code != null && THROTTLING_CODES.contains(code)) || e.isThrottlingException()) {
                return Optional.of(new ThrottledException(message, e, provider, code, retryAfter(e)));
            }
            if (code != null && ACCESS_DENIED_CODES.contains(code)) {
                return Optional.of(new AccessDeniedException(message, e, provider, code));
            }
            if ("ConditionalCheckFailedException".equals(code) || "TransactionConflictException".equals(code)) {
                return Optional.of(new PreconditionFailedException(message, e, provider, code));
            }
            if (code != null && NOT_FOUND_CODES.contains(code)) {
                return Optional.of(new NotFoundException(message, e, provider, code));
            }
            if ("InvalidObjectState".equals(code)) {
                return Optional.of(new InvalidArgumentException(
                        "Blob is archived and must be restored before this operation: " + message, e, provider, code));
            }
            CloudException mapped = switch (status) {
                case 404 -> new NotFoundException(message, e, provider, code);
                case 412 -> new PreconditionFailedException(message, e, provider, code);
                case 409 -> "ConditionalRequestConflict".equals(code)
                        ? new PreconditionFailedException(message, e, provider, code)
                        : new AlreadyExistsException(message, e, provider, code);
                case 401, 403 -> new AccessDeniedException(message, e, provider, code);
                case 400, 416 -> new InvalidArgumentException(message, e, provider, code);
                case 501 -> new UnsupportedFeatureException(message, e, provider, code);
                default -> status >= 500 ? new UnavailableException(message, e, provider, code) : null;
            };
            return Optional.ofNullable(mapped);
        }
        if (error instanceof SdkClientException e && e.retryable()) {
            return Optional.of(new UnavailableException(e.getMessage(), e, provider, null));
        }
        return Optional.empty();
    }

    private static Duration retryAfter(AwsServiceException e) {
        var details = e.awsErrorDetails();
        if (details == null || details.sdkHttpResponse() == null) {
            return null;
        }
        return details.sdkHttpResponse().firstMatchingHeader("Retry-After")
                .map(ThrottledException::parseRetryAfter).orElse(null);
    }
}
