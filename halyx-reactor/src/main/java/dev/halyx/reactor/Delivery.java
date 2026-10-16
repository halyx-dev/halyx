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

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A message or record on its way through a pipeline. The consumer that delivered it waits until it is
 * settled: {@link #ack()} acks the message, or commits the record; {@link #nack(Throwable)} returns it for
 * redelivery after the consumer's backoff, or retries the record, holding back its partition. Settling twice
 * has no further effect. A delivery left unsettled when its {@code Flux} is cancelled is nacked.
 *
 * @param <T> what was delivered: a {@code ReceivedMessage}, {@code ReceivedRecord} or decoded {@code CloudEvent}
 */
public final class Delivery<T> {

    private final T value;
    private final CompletableFuture<Void> settled;

    private Delivery(T value, CompletableFuture<Void> settled) {
        this.value = value;
        this.settled = settled;
    }

    static <T> Delivery<T> of(T value) {
        return new Delivery<>(Objects.requireNonNull(value, "value"), new CompletableFuture<>());
    }

    public T value() {
        return value;
    }

    /** Acks the message, or commits the record, once the consumer sees it. */
    public void ack() {
        settled.complete(null);
    }

    /** Returns it for redelivery, or retries the record; {@code cause} is what the consumer logs and counts. */
    public void nack(Throwable cause) {
        settled.completeExceptionally(Objects.requireNonNull(cause, "cause"));
    }

    public boolean isSettled() {
        return settled.isDone();
    }

    /** The same delivery carrying {@code other}: settling either settles both. */
    <U> Delivery<U> with(U other) {
        return new Delivery<>(Objects.requireNonNull(other, "value"), settled);
    }

    void cancel() {
        settled.completeExceptionally(new Cancelled());
    }

    boolean isCancelled() {
        return settled.isCompletedExceptionally() && settled.exceptionNow() instanceof Cancelled;
    }

    /**
     * Waits on the consumer's thread until settled; throws the nack's cause (a {@code CancellationException}
     * if cancelled), so the consumer nacks or retries.
     */
    void await() throws Exception {
        try {
            settled.join();
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw (Error) cause;
        }
    }

    @Override
    public String toString() {
        return "Delivery[" + value + (isSettled() ? ", settled" : "") + "]";
    }

    /** A delivery left unsettled when its pipeline was cancelled: expected, so it carries no stack trace. */
    static final class Cancelled extends CancellationException {
        Cancelled() {
            super("the pipeline was cancelled");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
