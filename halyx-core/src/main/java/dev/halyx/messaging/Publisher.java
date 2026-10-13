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
package dev.halyx.messaging;

import dev.halyx.capability.Capabilities;

import java.util.List;

/** Publishes to one topic or queue. Thread-safe; share one per topic. */
public interface Publisher {

    String provider();

    String topic();

    Capabilities capabilities();

    PublishResult publish(OutgoingMessage message);

    default PublishResult publish(String body) {
        return publish(OutgoingMessage.of(body));
    }

    /**
     * Publishes in as few requests as the provider allows (batches of 10 on SNS and SQS). If a batch
     * fails, earlier batches stay published.
     */
    List<PublishResult> publishAll(List<OutgoingMessage> messages);

    /** The native client, such as {@code SnsClient}. */
    <T> T unwrap(Class<T> nativeType);
}
