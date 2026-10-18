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
package dev.halyx.tck;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An executor for {@code async(executor)} that counts the blocking work it is given, to prove a driver's
 * non-blocking path deterministically: a call on that path never needs it, while the blocking fallback always does.
 */
public final class CountingExecutor implements Executor {

    private final AtomicInteger uses = new AtomicInteger();

    @Override
    public void execute(Runnable task) {
        uses.incrementAndGet();
        Thread.ofVirtual().start(task);
    }

    public int uses() {
        return uses.get();
    }

    /** Asserts that no call needed a thread to wait on. */
    public void assertUnused() {
        assertThat(uses.get()).as("blocking tasks run for non-blocking calls").isZero();
    }
}
