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
package dev.halyx.cache;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for one cache instance in this JVM, for hit ratios and dashboards. Spring integration
 * publishes them as Micrometer {@code cache.*} meters.
 */
public final class CacheStatistics {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder loadFailures = new LongAdder();
    private final LongAdder failOpenSkips = new LongAdder();

    /** Lookups that found a value (get, getAll per key, getOrLoad served from cache). */
    public long hits() {
        return hits.sum();
    }

    /** Lookups that found nothing. */
    public long misses() {
        return misses.sum();
    }

    public long puts() {
        return puts.sum();
    }

    /** Keys removed by delete, deleteAll, getAndDelete, deleteIfEquals and clear. */
    public long evictions() {
        return evictions.sum();
    }

    /** Successful loader calls from getOrLoad. */
    public long loads() {
        return loads.sum();
    }

    public long loadFailures() {
        return loadFailures.sum();
    }

    /** Operations skipped because the cache was unavailable and fail-open is on. */
    public long failOpenSkips() {
        return failOpenSkips.sum();
    }

    public double hitRatio() {
        long h = hits(), total = h + misses();
        return total == 0 ? Double.NaN : (double) h / total;
    }

    void hit(long n) { hits.add(n); }
    void miss(long n) { misses.add(n); }
    void put(long n) { puts.add(n); }
    void evicted(long n) { evictions.add(n); }
    void loaded() { loads.increment(); }
    void loadFailed() { loadFailures.increment(); }
    void failOpenSkip() { failOpenSkips.increment(); }

    @Override
    public String toString() {
        return "CacheStatistics[hits=" + hits() + ", misses=" + misses() + ", puts=" + puts() + ", evictions="
                + evictions() + ", loads=" + loads() + ", loadFailures=" + loadFailures() + ", failOpenSkips="
                + failOpenSkips() + "]";
    }
}
