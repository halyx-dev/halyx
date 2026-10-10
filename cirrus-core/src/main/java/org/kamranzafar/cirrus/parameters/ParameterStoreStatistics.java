package org.kamranzafar.cirrus.parameters;

import java.util.concurrent.atomic.LongAdder;

/** Read counters for one parameter store instance in this JVM. */
public final class ParameterStoreStatistics {

    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder backendReads = new LongAdder();
    private final LongAdder changes = new LongAdder();
    private final LongAdder watchFailures = new LongAdder();

    /** Reads served from the store's cache. */
    public long cacheHits() {
        return cacheHits.sum();
    }

    /** Reads that went to the backend. */
    public long backendReads() {
        return backendReads.sum();
    }

    /** Added, updated or removed values reported to watchers. */
    public long changesDetected() {
        return changes.sum();
    }

    /** Watch polls that failed. */
    public long watchFailures() {
        return watchFailures.sum();
    }

    void cacheHit() { cacheHits.increment(); }
    void backendRead() { backendReads.increment(); }
    void changed(long n) { changes.add(n); }
    void watchFailed() { watchFailures.increment(); }
}
