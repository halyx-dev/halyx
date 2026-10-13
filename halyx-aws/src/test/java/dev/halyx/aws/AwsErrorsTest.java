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
package dev.halyx.aws;

import org.junit.jupiter.api.Test;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.PreconditionFailedException;
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
