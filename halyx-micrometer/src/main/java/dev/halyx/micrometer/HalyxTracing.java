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
package dev.halyx.micrometer;

import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.EventPublisher;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.messaging.MessageListener;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.stream.RecordListener;
import dev.halyx.stream.StreamProducer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries the trace through Halyx' messages, events, stream records and function invocations with Micrometer Tracing,
 * with or without Spring: wrap publishers, producers and function invokers to send the current trace context, and
 * listeners to continue it.
 *
 * <pre>{@code
 * HalyxTracing tracing = new HalyxTracing(tracer, propagator);
 * Publisher orders = tracing.publisher(context.publisher("orders"));
 * subscription.listen(tracing.listener("billing", message -> bill(message)), ConsumerOptions.DEFAULTS);
 * }</pre>
 *
 * The Spring Boot starter applies all of these to its beans and {@code @HalyxListener} methods.
 */
public final class HalyxTracing {

    private final Tracer tracer;
    private final Propagator propagator;

    public HalyxTracing(Tracer tracer, Propagator propagator) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.propagator = Objects.requireNonNull(propagator, "propagator");
    }

    /** Adds the current trace context to each message's attributes. */
    public Publisher publisher(Publisher publisher) {
        return publisher instanceof TracingPublisher ? publisher : new TracingPublisher(publisher, tracer, propagator);
    }

    /** Adds the current trace context to each event's extension attributes. */
    public EventPublisher eventPublisher(EventPublisher publisher) {
        return publisher instanceof TracingEventPublisher ? publisher
                : new TracingEventPublisher(publisher, tracer, propagator);
    }

    /** Adds the current trace context to each record's headers. */
    public StreamProducer streamProducer(StreamProducer producer) {
        return producer instanceof TracingStreamProducer ? producer
                : new TracingStreamProducer(producer, tracer, propagator);
    }

    /** Adds the current trace context to each invocation's headers, and to a trigger's where it takes headers. */
    public FunctionInvoker functionInvoker(FunctionInvoker invoker) {
        return invoker instanceof TracingFunctionInvoker ? invoker
                : new TracingFunctionInvoker(invoker, tracer, propagator);
    }

    /**
     * Runs each invocation in a consumer span continuing the trace carried in the message: its attributes, or for
     * a message holding a CloudEvent, the event's extension attributes (a new trace when there is neither). The
     * span is tagged with the subscription and delivery attempt.
     */
    public MessageListener listener(String subscription, MessageListener listener) {
        return message -> {
            TraceCarrier carrier = new TraceCarrier(message);
            Span span = propagator.extract(carrier, TraceCarrier::get)
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

    /**
     * Runs each invocation in a consumer span continuing the trace carried in the record's headers (a new trace
     * when there is none). The span is tagged with the topic, partition, offset and delivery attempt.
     */
    public RecordListener recordListener(String consumer, RecordListener listener) {
        return record -> {
            Span span = propagator.extract(record.headers(), (carrier, key) -> carrier.get(key))
                    .name("halyx.record " + consumer)
                    .kind(Span.Kind.CONSUMER)
                    .tag("messaging.destination.name", record.topic())
                    .tag("messaging.destination.partition.id", String.valueOf(record.partition()))
                    .tag("messaging.kafka.offset", String.valueOf(record.offset()))
                    .tag("messaging.delivery.attempt", String.valueOf(record.deliveryAttempt()))
                    .start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                listener.onRecord(record);
            } catch (Exception | Error e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        };
    }

    /** A message's attributes, then the extensions of the CloudEvent it holds, decoded only if needed. */
    private static final class TraceCarrier {

        private final ReceivedMessage message;
        private Optional<CloudEvent> event;

        TraceCarrier(ReceivedMessage message) {
            this.message = message;
        }

        String get(String key) {
            String attribute = message.attributes().get(key);
            if (attribute != null) {
                return attribute;
            }
            return event().flatMap(e -> e.extension(key.toLowerCase(Locale.ROOT))).orElse(null);
        }

        private Optional<CloudEvent> event() {
            if (event == null) {
                event = mayHoldEvent() ? decode() : Optional.empty();
            }
            return event;
        }

        /** CloudEvents in binary mode, or a JSON body: anything else can't hold one, so isn't parsed. */
        private boolean mayHoldEvent() {
            if (message.attributes().keySet().stream().anyMatch(k -> k.endsWith("specversion"))) {
                return true;
            }
            for (byte b : message.body()) {
                if (!Character.isWhitespace(b)) {
                    return b == '{';
                }
            }
            return false;
        }

        private Optional<CloudEvent> decode() {
            try {
                return Optional.of(CloudEvents.decode(message));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
