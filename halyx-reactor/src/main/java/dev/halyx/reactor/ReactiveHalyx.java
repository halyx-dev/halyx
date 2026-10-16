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

import dev.halyx.Halyx;
import dev.halyx.cache.Cache;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Project Reactor views of a {@link Halyx}'s services: {@code Mono} and {@code Flux} over the blocking API,
 * run on a scheduler the application chooses. Halyx creates no threads of its own here.
 *
 * <pre>{@code
 * ReactiveHalyx rx = ReactiveHalyx.of(halyx, scheduler);          // or ReactiveHalyx.withVirtualThreads(halyx)
 * ReactiveBlobStore invoices = rx.provider("orders").blobStore("acme-invoices");
 * Mono<byte[]> body = invoices.getBytes(BlobKey.of("2026/inv-1.pdf"));
 * }</pre>
 *
 * Every call runs when subscribed, once per subscription ({@code retry()} calls again), on a thread of the
 * scheduler; Halyx's own retries, interceptors and observations still apply inside it. Errors are the blocking
 * API's {@code CloudException}s. Cancelling a call that has started doesn't stop it: it runs to completion and
 * its result is dropped, so a cancelled write may still take effect. The scheduler's threads block for the
 * length of each call, which suits virtual threads; Reactor's {@code boundedElastic} scheduler works too, and
 * {@code Schedulers.parallel()} must not be used. With Micrometer's context-propagation library on the
 * classpath, each call sees the subscriber's context (MDC, the current observation and so on).
 */
public final class ReactiveHalyx implements AutoCloseable {

    /** Items per batch, and the longest a partial batch waits, for the {@code Flux}-taking {@code ...All} methods. */
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final Duration DEFAULT_BATCH_WAIT = Duration.ofMillis(50);

    private final Halyx halyx;
    private final Calls calls;
    private final boolean ownsScheduler;

    private ReactiveHalyx(Builder b) {
        this.halyx = b.halyx;
        this.ownsScheduler = b.scheduler == null;
        Scheduler scheduler = b.scheduler != null ? b.scheduler : virtualThreadScheduler("halyx-reactor");
        this.calls = new Calls(scheduler, b.maxConcurrency);
    }

    /** Reactive views running on {@code scheduler}, which stays the application's to dispose of. */
    public static ReactiveHalyx of(Halyx halyx, Scheduler scheduler) {
        return builder(halyx).scheduler(scheduler).build();
    }

    /** Reactive views running on virtual threads, one per call; {@link #close()} disposes of the scheduler. */
    public static ReactiveHalyx withVirtualThreads(Halyx halyx) {
        return builder(halyx).build();
    }

    public static Builder builder(Halyx halyx) {
        return new Builder(halyx);
    }

    /**
     * A scheduler that runs each task on a new virtual thread named {@code name}: suited to blocking calls,
     * for applications that create their own. Dispose of it when done.
     */
    public static Scheduler virtualThreadScheduler(String name) {
        return Schedulers.fromExecutorService(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-", 0).factory()), name);
    }

    public ReactiveCloudContext provider(String name) {
        return new ReactiveCloudContext(halyx.provider(name), this);
    }

    public Set<String> names() {
        return halyx.names();
    }

    /** The blocking registry this wraps. */
    public Halyx blocking() {
        return halyx;
    }

    public Scheduler scheduler() {
        return calls.scheduler();
    }

    /**
     * Any blocking work as a {@code Mono}, run like the services' calls: when subscribed, once per subscription,
     * on this scheduler, with the subscriber's context and within the concurrency limit. A null result is an
     * empty {@code Mono}.
     */
    public <T> Mono<T> call(Callable<T> work) {
        return calls.mono(work);
    }

    /**
     * A blocking {@code Stream} as a {@code Flux}: opened when subscribed, its elements pulled as the subscriber
     * asks for them on this scheduler, and closed however the {@code Flux} ends.
     */
    public <T> Flux<T> stream(Callable<? extends Stream<T>> open) {
        return calls.stream(open);
    }

    public ReactiveBlobStore wrap(BlobStore store) {
        return new ReactiveBlobStore(store, calls);
    }

    public ReactiveDocumentStore wrap(DocumentStore store) {
        return new ReactiveDocumentStore(store, calls);
    }

    public ReactivePublisher wrap(Publisher publisher) {
        return new ReactivePublisher(publisher, calls);
    }

    public ReactiveSubscription wrap(Subscription subscription) {
        return new ReactiveSubscription(subscription, calls);
    }

    public ReactiveEventPublisher wrap(EventPublisher publisher) {
        return new ReactiveEventPublisher(publisher, calls);
    }

    public ReactiveStreamProducer wrap(StreamProducer producer) {
        return new ReactiveStreamProducer(producer, calls);
    }

    public ReactiveStreamConsumer wrap(StreamConsumer consumer) {
        return new ReactiveStreamConsumer(consumer, calls);
    }

    public <V> ReactiveCache<V> wrap(Cache<V> cache) {
        return new ReactiveCache<>(cache, calls);
    }

    public ReactiveParameterStore wrap(ParameterStore store) {
        return new ReactiveParameterStore(store, calls);
    }

    /** Disposes of the scheduler if this created it; never closes the {@link Halyx}, which stays the caller's. */
    @Override
    public void close() {
        if (ownsScheduler) {
            calls.scheduler().dispose();
        }
    }

    @Override
    public String toString() {
        return "ReactiveHalyx" + halyx.names();
    }

    public static final class Builder {
        private final Halyx halyx;
        private Scheduler scheduler;
        private int maxConcurrency;

        private Builder(Halyx halyx) {
            this.halyx = Objects.requireNonNull(halyx, "halyx");
        }

        /** Runs calls on {@code scheduler}, which stays the application's to dispose of. Defaults to virtual threads. */
        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            return this;
        }

        /**
         * Most calls running at once across every service of this {@code ReactiveHalyx}; others wait their turn
         * on their scheduler thread. Bounds what timeouts and retries of slow calls can pile up. 0, the default,
         * means no limit.
         */
        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 0) {
                throw new IllegalArgumentException("maxConcurrency must not be negative");
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public ReactiveHalyx build() {
            return new ReactiveHalyx(this);
        }
    }
}
