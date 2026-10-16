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
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A push consumer (a subscription's or a stream's listener) as a {@code Flux} of deliveries. The listener
 * hands each delivery to the subscriber and waits until it is settled, so the consumer's own concurrency
 * bounds what is in flight and a slow pipeline slows delivery. Cancelling the {@code Flux} nacks what is still
 * unsettled and stops the consumer on the scheduler, never on a listener's thread: Reactor can cancel from
 * inside {@code sink.next}, which runs on a listener's thread, and stopping waits for running listeners.
 */
final class Deliveries {

    private Deliveries() {
    }

    /** Starts a consumer for {@code listener}; what it returns is closed when the subscriber goes away. */
    interface Starter<T> {
        AutoCloseable start(Listener<T> listener) throws Exception;
    }

    @FunctionalInterface
    interface Listener<T> {
        void onDelivery(T value) throws Exception;
    }

    /**
     * @param onCancelled settles a delivery left unsettled by a cancelled pipeline from within the listener, so
     *                    the consumer leaves it alone, or null to fail the listener and have the consumer retry it
     */
    static <T> Flux<Delivery<T>> flux(Scheduler scheduler, Starter<T> starter, java.util.function.Consumer<T> onCancelled) {
        return Flux.create(sink -> {
            Set<Delivery<T>> pending = ConcurrentHashMap.newKeySet();
            AutoCloseable consumer;
            try {
                consumer = starter.start(value -> {
                    Delivery<T> delivery = Delivery.of(value);
                    pending.add(delivery);
                    try {
                        if (sink.isCancelled()) {
                            delivery.cancel();
                        } else {
                            sink.next(delivery);
                        }
                        delivery.await();
                    } catch (Delivery.Cancelled e) {
                        if (onCancelled == null) {
                            throw e;
                        }
                        onCancelled.accept(value);
                    } finally {
                        pending.remove(delivery);
                    }
                });
            } catch (Exception e) {
                sink.error(e);
                return;
            }
            sink.onDispose(() -> {
                pending.forEach(Delivery::cancel);
                scheduler.schedule(() -> {
                    try {
                        consumer.close();
                    } catch (Exception e) {
                        // already stopping; the subscriber has gone
                    }
                });
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * Runs {@code handler} for each delivery, up to {@code concurrency} at once: completing acks or commits it,
     * an error nacks it. Each handler's values are emitted once it has completed and its delivery is settled,
     * so everything emitted belongs to a delivery already acked or committed, and cancelling after a value never
     * returns that delivery for redelivery.
     */
    static <T, R> Flux<R> process(Flux<Delivery<T>> deliveries, int concurrency,
                                  Function<? super T, ? extends org.reactivestreams.Publisher<? extends R>> handler) {
        return deliveries.flatMap(delivery -> Flux.<R>defer(() -> Flux.from(handler.apply(delivery.value())))
                .collectList()
                .doOnNext(values -> delivery.ack())
                .doOnCancel(delivery::cancel)
                .flatMapIterable(values -> values)
                .onErrorResume(e -> {
                    delivery.nack(e);
                    return Mono.empty();
                }), concurrency);
    }
}
