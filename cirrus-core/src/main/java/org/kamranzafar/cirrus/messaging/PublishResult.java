package org.kamranzafar.cirrus.messaging;

import java.util.Objects;

/** @param messageId the provider's id of the published message */
public record PublishResult(String messageId) {

    public PublishResult {
        Objects.requireNonNull(messageId, "messageId");
    }
}
