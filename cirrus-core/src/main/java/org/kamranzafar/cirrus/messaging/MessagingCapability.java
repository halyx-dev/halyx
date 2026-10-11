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
package org.kamranzafar.cirrus.messaging;

import org.kamranzafar.cirrus.capability.Capability;

/** Messaging features that differ between providers. */
public enum MessagingCapability implements Capability {
    /**
     * {@link OutgoingMessage#orderingKey()}: messages with the same key are delivered in publish order, one
     * at a time (SQS FIFO message groups, Pub/Sub ordering keys).
     */
    ORDERING,
    /** {@link OutgoingMessage#deduplicationId()}: a repeat of a recent id is dropped (SQS FIFO, Service Bus). */
    DEDUPLICATION,
    /** {@link OutgoingMessage#delay()}: delivery held back for a while (SQS up to 15 minutes, Service Bus). */
    DELAYED_DELIVERY,
    /** {@code nack(delay)} holds the message back for the delay rather than redelivering it at once. */
    NACK_DELAY
}
