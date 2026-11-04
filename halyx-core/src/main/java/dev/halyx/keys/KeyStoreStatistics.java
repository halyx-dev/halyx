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
package dev.halyx.keys;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for one key store instance in this JVM: how many calls reached the key service, and how many the caches
 * saved. Key service calls are what quotas and bills count.
 */
public final class KeyStoreStatistics {

    private final LongAdder wraps = new LongAdder();
    private final LongAdder unwraps = new LongAdder();
    private final LongAdder unwrapCacheHits = new LongAdder();
    private final LongAdder reusedDataKeys = new LongAdder();
    private final LongAdder signs = new LongAdder();
    private final LongAdder macs = new LongAdder();
    private final LongAdder metadataReads = new LongAdder();
    private final LongAdder publicKeyReads = new LongAdder();

    /** Data keys wrapped by the key service. */
    public long wraps() {
        return wraps.sum();
    }

    /** Data keys unwrapped by the key service. */
    public long unwraps() {
        return unwraps.sum();
    }

    /** Unwraps served from the data key cache. */
    public long unwrapCacheHits() {
        return unwrapCacheHits.sum();
    }

    /** Messages sealed with a reused data key, so without a wrap call. */
    public long reusedDataKeys() {
        return reusedDataKeys.sum();
    }

    /** Digests signed by the key service. */
    public long signs() {
        return signs.sum();
    }

    /** MACs made or verified by the key service. */
    public long macs() {
        return macs.sum();
    }

    /** Key metadata reads. */
    public long metadataReads() {
        return metadataReads.sum();
    }

    /** Public keys fetched; verification itself makes no call. */
    public long publicKeyReads() {
        return publicKeyReads.sum();
    }

    void wrapped() {
        wraps.increment();
    }

    void unwrapped() {
        unwraps.increment();
    }

    void unwrapCacheHit() {
        unwrapCacheHits.increment();
    }

    void reusedDataKey() {
        reusedDataKeys.increment();
    }

    void signed() {
        signs.increment();
    }

    void maced() {
        macs.increment();
    }

    void metadataRead() {
        metadataReads.increment();
    }

    void publicKeyRead() {
        publicKeyReads.increment();
    }

    @Override
    public String toString() {
        return "KeyStoreStatistics[wraps=" + wraps() + ", unwraps=" + unwraps() + ", unwrapCacheHits="
                + unwrapCacheHits() + ", reusedDataKeys=" + reusedDataKeys() + ", signs=" + signs() + ", macs=" + macs()
                + "]";
    }
}
