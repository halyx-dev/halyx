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

import dev.halyx.micrometer.HalyxTracing;
import dev.halyx.messaging.MessageListener;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Runs each listener invocation in a consumer span continuing the trace carried in the message attributes, or the
 * extensions of the CloudEvent a message holds; see {@link HalyxTracing#listener}.
 */
public final class TracingListenerDecorator implements MessageListenerDecorator {

    private final HalyxTracing tracing;

    public TracingListenerDecorator(Tracer tracer, Propagator propagator) {
        this.tracing = new HalyxTracing(tracer, propagator);
    }

    @Override
    public MessageListener decorate(String subscription, MessageListener listener) {
        return tracing.listener(subscription, listener);
    }
}
