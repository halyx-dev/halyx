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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import dev.halyx.messaging.MessageListener;

/**
 * Runs each listener invocation in a consumer span continuing the trace carried in the message's attributes
 * (a new trace when there is none). The span is tagged with the subscription and delivery attempt.
 */
public final class TracingListenerDecorator implements MessageListenerDecorator {

    private final Tracer tracer;
    private final Propagator propagator;

    public TracingListenerDecorator(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public MessageListener decorate(String subscription, MessageListener listener) {
        return message -> {
            Span span = propagator.extract(message.attributes(), (carrier, key) -> carrier.get(key))
                    .name("halyx.message " + subscription)
                    .kind(Span.Kind.CONSUMER)
                    .tag("messaging.destination.name", subscription)
                    .tag("messaging.message.id", message.id())
                    .tag("messaging.delivery.attempt", String.valueOf(message.deliveryAttempt()))
                    .start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                listener.onMessage(message);
            } catch (Exception | Error e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        };
    }
}
