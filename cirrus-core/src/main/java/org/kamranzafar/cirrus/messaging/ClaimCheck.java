package org.kamranzafar.cirrus.messaging;

import org.kamranzafar.cirrus.storage.BlobStore;

import java.util.Objects;

/**
 * Sends bodies larger than {@code thresholdBytes} through a blob store instead of the message: the body
 * is stored under {@code prefix + <uuid>} and the message carries only that key (attribute
 * {@value #ATTRIBUTE}). Subscriptions with the same claim check fetch the body transparently. Use it for
 * payloads above the provider's limit (256 KiB on SNS, SQS and Service Bus standard; 10 MB on Pub/Sub).
 * Stored bodies are not deleted on ack (other subscriptions may still need them): expire them with a
 * bucket lifecycle rule.
 */
public record ClaimCheck(BlobStore store, int thresholdBytes, String prefix) {

    public static final String ATTRIBUTE = "cirrus-claim-check";

    public ClaimCheck {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(prefix, "prefix");
        if (thresholdBytes < 0) {
            throw new IllegalArgumentException("thresholdBytes must not be negative");
        }
    }

    /** Offloads bodies over 200 KiB, which leaves room for attributes within a 256 KiB message limit. */
    public static ClaimCheck of(BlobStore store) {
        return new ClaimCheck(store, 200 * 1024, "claim-check/");
    }
}
