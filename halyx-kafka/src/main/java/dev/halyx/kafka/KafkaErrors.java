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
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.ThrottlingQuotaExceededException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.WakeupException;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnknownCloudException;

import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Translates Kafka client exceptions to the portable error model. */
public final class KafkaErrors {

    private KafkaErrors() {
    }

    public static Optional<CloudException> translate(Throwable error, String provider) {
        Throwable e = unwrap(error);
        String code = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (e instanceof UnknownTopicOrPartitionException) {
            return Optional.of(new NotFoundException(message, e, provider, code));
        }
        if (e instanceof AuthorizationException || e instanceof AuthenticationException) {
            return Optional.of(new AccessDeniedException(message, e, provider, code));
        }
        if (e instanceof RecordTooLargeException || e instanceof RecordBatchTooLargeException
                || e instanceof InvalidTopicException || e instanceof InvalidConfigurationException
                || e instanceof ConfigException) {
            return Optional.of(new InvalidArgumentException(message, e, provider, code));
        }
        if (e instanceof ThrottlingQuotaExceededException) {
            return Optional.of(new ThrottledException(message, e, provider, code));
        }
        // A commit refused because the group rebalanced, or an interrupted wait: the records are read again.
        if (e instanceof TimeoutException || e instanceof RetriableException || e instanceof CommitFailedException
                || e instanceof RebalanceInProgressException || e instanceof InterruptException
                || e instanceof WakeupException) {
            return Optional.of(new UnavailableException(message, e, provider, code));
        }
        if (e instanceof KafkaException) {
            return Optional.of(new UnknownCloudException(message, e, provider, code));
        }
        return Optional.empty();
    }

    /** The Kafka exception inside future and construction wrappers. */
    static Throwable unwrap(Throwable error) {
        Throwable e = error;
        while ((e instanceof ExecutionException || e instanceof CompletionException
                || (e.getClass() == KafkaException.class)) && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
