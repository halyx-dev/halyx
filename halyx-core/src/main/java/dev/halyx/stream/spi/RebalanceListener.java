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

import java.util.Collection;

/**
 * Told when the group moves partitions to or from this member. Drivers call it from inside
 * {@link ConsumerDriver#poll}, on the polling thread, so it may commit through the driver.
 */
public interface RebalanceListener {

    /** Partitions this member now owns. */
    void onAssigned(Collection<Partition> partitions);

    /** Partitions this member is giving up; commit what was handled before returning. */
    void onRevoked(Collection<Partition> partitions);

    /** Partitions already taken over by another member, where committing is no longer possible. */
    default void onLost(Collection<Partition> partitions) {
        onRevoked(partitions);
    }
}
