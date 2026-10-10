package org.kamranzafar.cirrus.azure.servicebus;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import org.kamranzafar.cirrus.azure.AzureProvider;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;

import java.util.Optional;

/** Translates Service Bus (AMQP) failures to the portable error model. */
final class ServiceBusErrors {

    private ServiceBusErrors() {
    }

    static Optional<CloudException> translate(Throwable error) {
        String provider = AzureProvider.ID;
        if (error instanceof ServiceBusException e) {
            ServiceBusFailureReason reason = e.getReason();
            String code = reason == null ? null : reason.toString();
            String message = e.getMessage();
            if (ServiceBusFailureReason.MESSAGING_ENTITY_NOT_FOUND.equals(reason)) {
                return Optional.of(new NotFoundException(message, e, provider, code));
            }
            if (ServiceBusFailureReason.UNAUTHORIZED.equals(reason)) {
                return Optional.of(new AccessDeniedException(message, e, provider, code));
            }
            if (ServiceBusFailureReason.MESSAGE_LOCK_LOST.equals(reason)
                    || ServiceBusFailureReason.SESSION_LOCK_LOST.equals(reason)) {
                return Optional.of(new PreconditionFailedException("Lock lost (the lease ran out): " + message, e, provider, code));
            }
            if (ServiceBusFailureReason.QUOTA_EXCEEDED.equals(reason) || ServiceBusFailureReason.SERVICE_BUSY.equals(reason)) {
                return Optional.of(new ThrottledException(message, e, provider, code));
            }
            if (ServiceBusFailureReason.MESSAGE_SIZE_EXCEEDED.equals(reason)) {
                return Optional.of(new InvalidArgumentException(message, e, provider, code));
            }
            if (e.isTransient() || ServiceBusFailureReason.SERVICE_COMMUNICATION_ERROR.equals(reason)
                    || ServiceBusFailureReason.SERVICE_TIMEOUT.equals(reason)) {
                return Optional.of(new UnavailableException(message, e, provider, code));
            }
        }
        if (error instanceof AmqpException e) {
            AmqpErrorCondition condition = e.getErrorCondition();
            String code = condition == null ? null : condition.getErrorCondition();
            if (AmqpErrorCondition.NOT_FOUND.equals(condition)) {
                return Optional.of(new NotFoundException(e.getMessage(), e, provider, code));
            }
            if (e.isTransient()) {
                return Optional.of(new UnavailableException(e.getMessage(), e, provider, code));
            }
        }
        return Optional.empty();
    }
}
