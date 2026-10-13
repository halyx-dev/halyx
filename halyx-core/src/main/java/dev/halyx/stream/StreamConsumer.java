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
package dev.halyx.stream;

import dev.halyx.capability.Capabilities;

import java.time.Duration;
import java.util.List;

/**
 * One member of a consumer group on one topic. Read by hand with {@link #poll} and {@link #commit}, or run a
 * listener with {@link #listen}; not both. Not thread-safe for {@code poll} and {@code commit}: call them from
 * one thread. Close it to leave the group, which hands its partitions to the other members.
 */
public interface StreamConsumer extends AutoCloseable {

    String provider();

    String topic();

    String group();

    Capabilities capabilities();

    /**
     * The next records from the partitions this member owns, waiting up to {@code wait} for some to arrive.
     * Records come in order within each partition. Joining the group happens on the first call.
     */
    List<ReceivedRecord> poll(Duration wait);

    /**
     * Commits the position after every record {@link #poll} has returned, so the group won't read them again.
     * Partitions handed to another member since the last poll are skipped.
     */
    void commit();

    /** Runs {@code listener} on every record until the returned listener (or this consumer) is closed. */
    StreamListener listen(RecordListener listener, ListenerOptions options);

    default StreamListener listen(RecordListener listener) {
        return listen(listener, ListenerOptions.DEFAULTS);
    }

    /** The native client, such as Kafka's {@code Consumer}. Use it only from the thread that polls. */
    <T> T unwrap(Class<T> nativeType);

    /** Stops any listener, and leaves the group. */
    @Override
    void close();
}
