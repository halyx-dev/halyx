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

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One consumer group member on one topic. Every method except {@link #wakeup()} is called from a single
 * polling thread, as Kafka's consumer requires.
 */
public interface ConsumerDriver extends AutoCloseable {

    String provider();

    String topic();

    String group();

    Capabilities capabilities();

    /** Joins the group; rebalances are reported to {@code listener} from inside {@link #poll}. */
    void subscribe(RebalanceListener listener);

    /**
     * Records from owned, unpaused partitions, waiting up to {@code wait}. Returns early, possibly empty, after
     * {@link #wakeup()}.
     */
    List<DriverRecord> poll(Duration wait);

    /**
     * The partitions this member owns now. A listener that takes over from a stopped one on the same member
     * starts with them, since re-subscribing doesn't report them again.
     */
    Set<Partition> assignment();

    /** Makes the next {@link #poll} of {@code partition}, which this member owns, start at {@code offset}. */
    void seek(Partition partition, long offset);

    /** Stops reading these partitions until {@link #resume}; ownership is unaffected. */
    void pause(Collection<Partition> partitions);

    void resume(Collection<Partition> partitions);

    /** Commits the next offset to read for each partition, and waits for the broker to store it. */
    void commit(Map<Partition, Long> nextOffsets);

    /** Commits without waiting; a failure is only logged, since a later commit supersedes it. */
    void commitAsync(Map<Partition, Long> nextOffsets);

    /** Makes a blocked {@link #poll} return; safe from any thread. */
    void wakeup();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    /** Leaves the group. */
    @Override
    void close();
}
