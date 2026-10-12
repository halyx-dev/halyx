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
package org.kamranzafar.halyx.parameters;

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
