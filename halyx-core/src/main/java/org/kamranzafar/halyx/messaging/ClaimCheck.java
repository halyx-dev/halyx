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
package org.kamranzafar.halyx.messaging;

import org.kamranzafar.halyx.storage.BlobStore;

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

    public static final String ATTRIBUTE = "halyx-claim-check";

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
