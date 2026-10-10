package org.kamranzafar.cirrus.storage;

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
