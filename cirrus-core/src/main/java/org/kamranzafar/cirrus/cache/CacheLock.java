package org.kamranzafar.cirrus.cache;

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
