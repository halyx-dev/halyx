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
package org.kamranzafar.halyx.cache;

import java.time.Duration;

/**
 * A lease-based distributed lock held in the cache. The lease expires on its own if the holder dies;
 * release and extend only succeed while this holder still owns it (a random token is compared
 * atomically), so a holder whose lease ran out can never release someone else's lock.
 *
 * <p>Like any lease lock this is advisory: protect work that must not run twice with fencing or
 * idempotency too, because a paused holder may outlive its lease.
 */
public interface CacheLock extends AutoCloseable {

    String name();

    /** Releases the lock. Returns false if the lease had already expired or been taken over. */
    boolean release();

    /** Extends the lease to {@code lease} from now. Returns false if the lock is no longer held. */
    boolean extend(Duration lease);

    /** Same as {@link #release()}, for try-with-resources. */
    @Override
    default void close() {
        release();
    }
}
