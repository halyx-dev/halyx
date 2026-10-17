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

import dev.halyx.capability.Capabilities;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.messaging.AsyncSubscription;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * A {@link Subscription} as a {@code Flux} of deliveries, plus {@code Mono}s for pulling and settling messages
 * by hand.
 *
 * <pre>{@code
 * billing.process(ConsumerOptions.DEFAULTS, message -> bill(message))   // completes: acked; fails: nacked
 *        .subscribe();
 * }</pre>
 *
 * Each subscriber starts a consumer of its own, and stops it when it goes away. The consumer waits for every
 * delivery to be settled, so at most {@code options.concurrency()} messages are in flight, and a slow pipeline
 * slows delivery. Delivery is at least once: make handling idempotent.
 */
public final class ReactiveSubscription {

    private final Subscription subscription;
    private final Calls calls;

    ReactiveSubscription(Subscription subscription, Calls calls) {
        this.subscription = Objects.requireNonNull(subscription, "subscription");
        this.calls = calls;
    }

    /**
     * The subscription's asynchronous view for one call, whose blocking work (where the driver has no non-blocking
     * path) runs on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncSubscription async(Executor blocking) {
        AsyncSubscription view = subscription.async(blocking);
        return view != null ? view : AsyncSubscription.onExecutor(subscription, blocking); // a mock or proxy without one
    }

    public String provider() {
        return subscription.provider();
    }

    public String name() {
        return subscription.name();
    }

    public Capabilities capabilities() {
        return subscription.capabilities();
    }

    /** The blocking subscription this wraps. */
    public Subscription blocking() {
        return subscription;
    }

    /**
     * Messages as they arrive, each to be settled with {@link Delivery#ack()} or {@link Delivery#nack}; what is
     * unsettled when the {@code Flux} is cancelled is nacked.
     */
    public Flux<Delivery<ReceivedMessage>> deliveries(ConsumerOptions options) {
        // A delivery left unsettled by a cancelled pipeline is nacked from the listener, for redelivery now.
        return Deliveries.flux(calls.scheduler(), listener -> subscription.listen(listener::onDelivery, options),
                message -> message.nack(Duration.ZERO));
    }

    public Flux<Delivery<ReceivedMessage>> deliveries() {
        return deliveries(ConsumerOptions.DEFAULTS);
    }

    /**
     * Runs {@code handler} for each message, up to {@code options.concurrency()} at once. A handler that
     * completes acks its message; one that fails nacks it for redelivery after the consumer's backoff. A
     * handler's values are emitted once it has completed and its message is acked.
     */
    public <R> Flux<R> process(ConsumerOptions options, Function<ReceivedMessage, ? extends Publisher<? extends R>> handler) {
        return Deliveries.process(deliveries(options), options.concurrency(), handler);
    }

    /**
     * Messages decoded as CloudEvents, from an event bus's queue (see {@link CloudEvents#decode}). A message that
     * holds no CloudEvent is nacked, so the consumer's retries and dead-lettering apply to it.
     */
    public Flux<Delivery<CloudEvent>> events(ConsumerOptions options) {
        return deliveries(options).concatMap(delivery -> {
            try {
                return Mono.just(delivery.with(CloudEvents.decode(delivery.value())));
            } catch (IllegalArgumentException e) {
                delivery.nack(e);
                return Mono.empty();
            }
        });
    }

    /** Like {@link #process}, for messages decoded as CloudEvents; one that holds no CloudEvent is nacked. */
    public <R> Flux<R> processEvents(ConsumerOptions options, Function<CloudEvent, ? extends Publisher<? extends R>> handler) {
        return Deliveries.process(events(options), options.concurrency(), handler);
    }

    /** Up to {@code max} messages, waiting up to {@code wait}; settle them with {@link #ack} and {@link #nack}. */
    public Mono<List<ReceivedMessage>> receive(int max, Duration wait) {
        return calls.future(blocking -> async(blocking).receive(max, wait));
    }

    public Mono<Void> ack(ReceivedMessage message) {
        return calls.future(blocking -> message.ackAsync()).then();
    }

    public Mono<Void> nack(ReceivedMessage message, Duration delay) {
        return calls.future(blocking -> message.nackAsync(delay)).then();
    }

    public Mono<Void> extendLease(ReceivedMessage message, Duration lease) {
        return calls.future(blocking -> message.extendLeaseAsync(lease)).then();
    }

    public Duration defaultLease() {
        return subscription.defaultLease();
    }

    public <T> T unwrap(Class<T> nativeType) {
        return subscription.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveSubscription[" + subscription + "]";
    }
}
