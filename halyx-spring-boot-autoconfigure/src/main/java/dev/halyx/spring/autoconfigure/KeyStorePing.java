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
package dev.halyx.spring.autoconfigure;

import dev.halyx.keys.KeyStore;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * A key store's health ping, answered from its last result for {@link #TTL}. Key services meter requests, some with a
 * low account-wide quota (AWS KMS's {@code ListAliases}), and every pod's probes would otherwise spend it. A failure is
 * kept too, so probes don't add load to a service that is throttling or down; recovery shows within the same time.
 * One probe pings at a time, and the others wait for its answer.
 */
public final class KeyStorePing {

    /** How long one ping's answer stands. */
    public static final Duration TTL = Duration.ofSeconds(30);

    private record Answer(long atNanos, RuntimeException failure) {
    }

    private final KeyStore store;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock(); // not synchronized: a ping is network I/O
    private volatile Answer last;

    public KeyStorePing(KeyStore store) {
        this(store, TTL, System::nanoTime);
    }

    KeyStorePing(KeyStore store, Duration ttl, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
    }

    /** Returns if the key service answered within the last {@link #TTL}; else throws what the ping threw. */
    public void check() {
        Answer answer = last;
        if (stale(answer)) {
            lock.lock();
            try {
                answer = last;
                if (stale(answer)) {
                    answer = ping();
                    last = answer;
                }
            } finally {
                lock.unlock();
            }
        }
        if (answer.failure() != null) {
            throw answer.failure();
        }
    }

    private boolean stale(Answer answer) {
        return answer == null || clock.getAsLong() - answer.atNanos() >= ttlNanos;
    }

    private Answer ping() {
        try {
            store.ping();
            return new Answer(clock.getAsLong(), null);
        } catch (RuntimeException e) {
            return new Answer(clock.getAsLong(), e);
        }
    }
}
