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
package dev.halyx.kafka;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.ThrottlingQuotaExceededException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnknownCloudException;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaErrorsTest {

    private static CloudException translate(Throwable error) {
        return KafkaErrors.translate(error, "test").orElseThrow();
    }

    @Test
    void unknownTopicIsNotFound() {
        assertThat(translate(new UnknownTopicOrPartitionException("no topic"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void authenticationAndAuthorizationAreAccessDenied() {
        assertThat(translate(new SaslAuthenticationException("bad token"))).isInstanceOf(AccessDeniedException.class);
        assertThat(translate(new TopicAuthorizationException(Set.of("t")))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void timeoutsAndRebalancesAreRetryable() {
        CloudException timeout = translate(new TimeoutException("metadata"));
        assertThat(timeout).isInstanceOf(UnavailableException.class);
        assertThat(timeout.isRetryable()).isTrue();
        assertThat(translate(new CommitFailedException())).isInstanceOf(UnavailableException.class);
    }

    @Test
    void tooLargeIsInvalidAndQuotaIsThrottled() {
        assertThat(translate(new RecordTooLargeException("big"))).isInstanceOf(InvalidArgumentException.class);
        assertThat(translate(new ThrottlingQuotaExceededException("slow"))).isInstanceOf(ThrottledException.class);
    }

    @Test
    void wrappersAreLookedThrough() {
        assertThat(translate(new ExecutionException(new UnknownTopicOrPartitionException("x"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(translate(new KafkaException("Failed to construct kafka producer", new SaslAuthenticationException("x"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void otherKafkaErrorsAreUnknownAndOthersAreLeftAlone() {
        assertThat(translate(new KafkaException("odd"))).isInstanceOf(UnknownCloudException.class);
        assertThat(KafkaErrors.translate(new IllegalStateException("not kafka"), "test")).isEmpty();
    }
}
