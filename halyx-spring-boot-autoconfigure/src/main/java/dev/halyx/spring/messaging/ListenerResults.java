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
package dev.halyx.spring.messaging;

import org.springframework.util.ClassUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Waits for what a {@code @HalyxListener} method returns: a {@code CompletionStage} (such as a
 * {@code CompletableFuture}), or a Reactive Streams {@code Publisher} (such as a Reactor {@code Mono} or
 * {@code Flux}), whose values are ignored. Completing settles the message or record as returning does; failing
 * settles it as throwing does. Anything else, including {@code void}, is already complete.
 */
final class ListenerResults {

    private static final boolean REACTIVE_STREAMS =
            ClassUtils.isPresent("org.reactivestreams.Publisher", ListenerResults.class.getClassLoader());

    private ListenerResults() {
    }

    static void await(Object result) throws Exception {
        CompletableFuture<?> done;
        if (result instanceof CompletionStage<?> stage) {
            done = stage.toCompletableFuture();
        } else if (REACTIVE_STREAMS && result instanceof org.reactivestreams.Publisher<?> publisher) {
            done = PublisherCompletion.of(publisher);
        } else {
            return;
        }
        try {
            done.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw (Error) cause;
        }
    }

    /** Subscribes, takes everything, and completes when the publisher does. */
    private static final class PublisherCompletion {
        static CompletableFuture<Void> of(org.reactivestreams.Publisher<?> publisher) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            publisher.subscribe(new org.reactivestreams.Subscriber<Object>() {
                @Override
                public void onSubscribe(org.reactivestreams.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Object value) {
                }

                @Override
                public void onError(Throwable error) {
                    done.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    done.complete(null);
                }
            });
            return done;
        }
    }
}
