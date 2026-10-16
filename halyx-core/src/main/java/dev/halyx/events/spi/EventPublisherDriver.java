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
package dev.halyx.events.spi;

import dev.halyx.error.CloudException;
import dev.halyx.events.CloudEvent;

import java.util.List;

/** A provider's event publisher. */
public interface EventPublisherDriver extends AutoCloseable {

    String provider();

    String bus();

    /** Publishes in one request; throws if any event wasn't accepted. */
    void publish(List<CloudEvent> events);

    /** Most events per request (EventBridge: 10). */
    int maxBatchSize();

    /** Largest event the router accepts, in its CloudEvents JSON form. */
    long maxEventBytes();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
