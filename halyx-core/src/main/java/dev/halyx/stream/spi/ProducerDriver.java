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
package dev.halyx.stream.spi;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.SendResult;

import java.util.List;

/**
 * Sends to one topic. Core checks capabilities, sizes and reserved headers. Must be thread-safe; a
 * transactional driver commits each {@link #send} call as one transaction.
 */
public interface ProducerDriver extends AutoCloseable {

    String provider();

    String topic();

    Capabilities capabilities();

    /** Sends the records and waits for the broker to store all of them; results in the same order. */
    List<SendResult> send(List<OutgoingRecord> records);

    /** The topic's partition count, from the cluster's metadata. */
    int partitionCount();

    /** Largest value the cluster accepts. */
    long maxRecordBytes();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
