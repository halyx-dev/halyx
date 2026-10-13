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
package dev.halyx.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** Runs a function over a list on virtual threads, at most {@code concurrency} at once, keeping input order. */
final class Bounded {

    private Bounded() {
    }

    static <T, R> List<R> map(List<T> items, int concurrency, Function<T, R> task) {
        if (items.size() <= 1 || concurrency <= 1) {
            return items.stream().map(task).toList();
        }
        Semaphore slots = new Semaphore(concurrency);
        List<Future<R>> futures = new ArrayList<>(items.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (T item : items) {
                slots.acquireUninterruptibly();
                futures.add(executor.submit(() -> {
                    try {
                        return task.apply(item);
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        List<R> results = new ArrayList<>(futures.size());
        for (Future<R> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
        return results;
    }
}
