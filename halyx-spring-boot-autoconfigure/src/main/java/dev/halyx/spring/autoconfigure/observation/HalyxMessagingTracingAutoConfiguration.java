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
package dev.halyx.spring.autoconfigure.observation;

import dev.halyx.events.EventPublisher;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.messaging.Publisher;
import dev.halyx.micrometer.HalyxTracing;
import dev.halyx.micrometer.TracingEventPublisher;
import dev.halyx.micrometer.TracingFunctionInvoker;
import dev.halyx.micrometer.TracingPublisher;
import dev.halyx.micrometer.TracingStreamProducer;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.messaging.MessageListenerDecorator;
import dev.halyx.spring.messaging.TracingListenerDecorator;
import dev.halyx.spring.stream.RecordListenerDecorator;
import dev.halyx.spring.stream.TracingRecordListenerDecorator;
import dev.halyx.stream.StreamProducer;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Carries trace context through messages, events, stream records and function invocations when the application uses
 * Micrometer Tracing: publishers, event publishers, stream producers and function invokers add it to attributes,
 * extensions and headers, and {@code @HalyxListener} methods continue it, all through {@link HalyxTracing}. Disable
 * with {@code halyx.observations.enabled=false}.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class, afterName = {
        "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration" // Boot 4
})
@ConditionalOnClass({Tracer.class, Publisher.class, HalyxTracing.class})
@ConditionalOnBean({Tracer.class, Propagator.class})
@ConditionalOnProperty(prefix = "halyx.observations", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HalyxMessagingTracingAutoConfiguration {

    @Bean
    public MessageListenerDecorator halyxTracingListenerDecorator(Tracer tracer, Propagator propagator) {
        return new TracingListenerDecorator(tracer, propagator);
    }

    @Bean
    public RecordListenerDecorator halyxTracingRecordListenerDecorator(Tracer tracer, Propagator propagator) {
        return new TracingRecordListenerDecorator(tracer, propagator);
    }

    /**
     * Wraps every {@code Publisher}, {@code EventPublisher}, {@code StreamProducer} and {@code FunctionInvoker} bean;
     * the tracer is looked up when the first of them is created.
     */
    @Bean
    public static BeanPostProcessor halyxTracingPostProcessor(ObjectProvider<Tracer> tracer,
                                                              ObjectProvider<Propagator> propagator) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof Publisher || bean instanceof EventPublisher || bean instanceof StreamProducer
                        || bean instanceof FunctionInvoker)
                        || bean instanceof TracingPublisher || bean instanceof TracingEventPublisher
                        || bean instanceof TracingStreamProducer || bean instanceof TracingFunctionInvoker) {
                    return bean;
                }
                Tracer t = tracer.getIfAvailable();
                Propagator p = propagator.getIfAvailable();
                if (t == null || p == null) {
                    return bean;
                }
                HalyxTracing tracing = new HalyxTracing(t, p);
                return switch (bean) {
                    case Publisher publisher -> tracing.publisher(publisher);
                    case EventPublisher publisher -> tracing.eventPublisher(publisher);
                    case StreamProducer producer -> tracing.streamProducer(producer);
                    case FunctionInvoker invoker -> tracing.functionInvoker(invoker);
                    default -> bean;
                };
            }
        };
    }
}
