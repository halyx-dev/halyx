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
package dev.halyx.reactor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Turns blocking Halyx calls into lazy publishers on the application's scheduler. A call runs once per
 * subscription, on a thread of {@link #scheduler}, with the subscriber's context restored (see
 * {@link ContextPropagation}), and within the optional concurrency limit.
 */
final class Calls {

    private final Scheduler scheduler;
    private final Semaphore limit;
    private final ContextPropagation context = ContextPropagation.detect();

    Calls(Scheduler scheduler, int maxConcurrency) {
        this.scheduler = scheduler;
        this.limit = maxConcurrency > 0 ? new Semaphore(maxConcurrency, true) : null;
    }

    Scheduler scheduler() {
        return scheduler;
    }

    /** A call whose null result means an empty Mono. */
    <T> Mono<T> mono(Callable<T> call) {
        return Mono.deferContextual(subscriberContext -> {
            // Captured on the subscribing thread, before moving to the scheduler: its thread locals and the
            // subscriber's Reactor context.
            Object snapshot = context.capture(subscriberContext);
            return Mono.<T>create(sink -> {
                Call<T> running = new Call<>(sink, () -> context.call(snapshot, call));
                sink.onCancel(running::cancel);
                try {
                    scheduler.schedule(running);
                } catch (RejectedExecutionException e) {
                    sink.error(e);
                }
            });
        });
    }

    /**
     * The stream's elements, pulled as the subscriber asks for them, each on the scheduler with the subscriber's
     * context restored; the stream is closed however the {@code Flux} ends. Opening counts towards the
     * concurrency limit; the pulls that follow, which may read further pages, don't.
     */
    <T> Flux<T> stream(Callable<? extends Stream<T>> open) {
        return Flux.deferContextual(subscriberContext -> {
            Object snapshot = context.capture(subscriberContext);
            return Flux.<T, Stream<T>>using(() -> context.call(snapshot, () -> limited(open)),
                    stream -> {
                        Iterator<T> iterator = stream.iterator();
                        return Flux.<T>generate(sink -> {
                            try {
                                boolean more = context.call(snapshot, () -> {
                                    if (iterator.hasNext()) {
                                        sink.next(iterator.next());
                                        return true;
                                    }
                                    return false;
                                });
                                if (!more) {
                                    sink.complete();
                                }
                            } catch (Exception e) {
                                sink.error(e);
                            }
                        });
                    },
                    Stream::close)
                    .subscribeOn(scheduler);
        });
    }

    <T> Mono<T> optional(Callable<Optional<T>> call) {
        return mono(() -> call.call().orElse(null));
    }

    Mono<Void> run(ThrowingRunnable call) {
        return mono(() -> {
            call.run();
            return null;
        });
    }

    /**
     * Every page's items, read as the subscriber asks for them: at most one page ahead of what it has
     * requested, so the next page is usually ready when it is wanted, and none after cancellation.
     */
    <P, T> Flux<T> pages(Callable<P> first, Function<P, Optional<Callable<P>>> next, Function<P, List<T>> items) {
        return mono(first)
                .expand(page -> next.apply(page).map(this::mono).orElse(Mono.empty()), 1)
                .concatMapIterable(items, 1);
    }

    /**
     * Groups {@code items} into batches of up to {@code batchSize}, flushing a partial batch after
     * {@code maxWait}, and sends one batch at a time, in order, so a slow send holds back the publisher.
     */
    <T, R> Flux<R> batches(org.reactivestreams.Publisher<? extends T> items, int batchSize, java.time.Duration maxWait,
                           Function<List<T>, Callable<List<R>>> send) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return Flux.<T>from(items)
                .bufferTimeout(batchSize, maxWait, true)
                .concatMap(batch -> mono(send.apply(batch)).flatMapIterable(results -> results), 0);
    }

    /**
     * An asynchronous call: started when subscribed, with the subscriber's context restored while it starts (so its
     * observation and trace follow it), and cancelled with the subscription. No thread waits for the result. The call
     * gets an executor for any blocking work it can't avoid: the scheduler, with the same context restored. The result
     * is delivered on the scheduler. With a concurrency limit, the permit is taken on the scheduler and released when
     * the call ends.
     */
    <T> Mono<T> future(Function<Executor, CompletableFuture<T>> call) {
        return Mono.deferContextual(subscriberContext -> {
            Object snapshot = context.capture(subscriberContext);
            Executor blocking = task -> scheduler.schedule(() -> {
                try {
                    context.call(snapshot, () -> {
                        task.run();
                        return null;
                    });
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Mono<T> started = Mono.fromFuture(() -> {
                try {
                    return context.call(snapshot, () -> call.apply(blocking));
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            });
            // Results are delivered on the scheduler, as for every other call: never on an SDK's network threads,
            // which the subscriber's own work would hold up, nor on the subscriber's thread when the call was quick.
            if (limit == null) {
                return started.publishOn(scheduler);
            }
            return Mono.using(() -> {
                limit.acquire();
                return limit;
            }, permit -> started, Semaphore::release).subscribeOn(scheduler).publishOn(scheduler);
        });
    }

    private <T> T limited(Callable<T> call) throws Exception {
        if (limit == null) {
            return call.call();
        }
        limit.acquire();
        try {
            return call.call();
        } finally {
            limit.release();
        }
    }

    /**
     * One call on the scheduler. Cancelled before it starts, it never runs, and a wait for the concurrency limit is
     * interrupted; once started, it runs to completion and its result or error is dropped. Its task is never disposed,
     * since that would interrupt the call (with {@code Schedulers.fromExecutorService} and {@code boundedElastic})
     * and could abort a blocking SDK call partway through.
     */
    private final class Call<T> implements Runnable {

        private static final int NEW = 0, WAITING = 1, RUNNING = 2, CANCELLED = 3;

        private final MonoSink<T> sink;
        private final Callable<T> call;
        private final AtomicInteger state = new AtomicInteger(NEW);
        private volatile boolean cancelled;
        private Thread waiter;

        Call(MonoSink<T> sink, Callable<T> call) {
            this.sink = sink;
            this.call = call;
        }

        @Override
        public void run() {
            if (limit == null ? !state.compareAndSet(NEW, RUNNING) : !acquire()) {
                return;
            }
            T result;
            try {
                result = call.call();
            } catch (Throwable e) {
                if (!cancelled) {
                    sink.error(e);
                }
                return;
            } finally {
                if (limit != null) {
                    limit.release();
                }
            }
            if (!cancelled) {
                sink.success(result);
            }
        }

        /** Takes a permit, unless cancelled first or while waiting. */
        private boolean acquire() {
            synchronized (this) {
                waiter = Thread.currentThread();
                if (!state.compareAndSet(NEW, WAITING)) {
                    return false;
                }
            }
            boolean acquired = false;
            try {
                limit.acquire();
                acquired = true;
            } catch (InterruptedException e) {
                // From cancel(), which has set CANCELLED; any other interrupt fails the call
                if (state.get() != CANCELLED) {
                    Thread.currentThread().interrupt();
                    sink.error(e);
                    return false;
                }
            }
            synchronized (this) {
                waiter = null;
                if (state.compareAndSet(WAITING, RUNNING)) {
                    return true;
                }
                // Cancelled while waiting, or just after the permit came: give it back, and clear cancel()'s interrupt
                if (acquired) {
                    limit.release();
                }
                Thread.interrupted();
                return false;
            }
        }

        void cancel() {
            cancelled = true;
            synchronized (this) {
                if (state.compareAndSet(WAITING, CANCELLED)) {
                    waiter.interrupt();
                } else {
                    state.compareAndSet(NEW, CANCELLED);
                }
            }
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
