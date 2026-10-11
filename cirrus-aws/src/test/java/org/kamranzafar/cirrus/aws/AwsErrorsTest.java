package org.kamranzafar.cirrus.aws;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import static org.assertj.core.api.Assertions.assertThat;

class AwsErrorsTest {

    private static AwsServiceException error(int status, String code) {
        return AwsServiceException.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("boom").build()).build();
    }

    @Test
    void accessDeniedIsRecognisedByCodeEvenWithStatus400() {
        // Secrets Manager, SSM and DynamoDB answer an IAM denial with 400 AccessDeniedException.
        assertThat(AwsErrors.translate(error(400, "AccessDeniedException"))).get().isInstanceOf(AccessDeniedException.class);
        assertThat(AwsErrors.translate(error(403, "AccessDenied"))).get().isInstanceOf(AccessDeniedException.class);
        assertThat(AwsErrors.translate(error(400, "ExpiredTokenException"))).get().isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void otherBadRequestsStayInvalidArguments() {
        assertThat(AwsErrors.translate(error(400, "ValidationException"))).get().isInstanceOf(InvalidArgumentException.class);
        assertThat(AwsErrors.translate(error(400, "ConditionalCheckFailedException"))).get()
                .isInstanceOf(PreconditionFailedException.class);
    }
}
