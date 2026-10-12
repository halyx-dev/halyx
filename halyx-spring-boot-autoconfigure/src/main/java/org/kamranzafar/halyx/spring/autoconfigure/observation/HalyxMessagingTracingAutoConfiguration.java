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
package org.kamranzafar.halyx.spring.autoconfigure.observation;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.kamranzafar.halyx.messaging.Publisher;
import org.kamranzafar.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.kamranzafar.halyx.spring.messaging.MessageListenerDecorator;
import org.kamranzafar.halyx.spring.messaging.TracingListenerDecorator;
import org.kamranzafar.halyx.spring.messaging.TracingPublisher;
import org.kamranzafar.halyx.spring.stream.RecordListenerDecorator;
import org.kamranzafar.halyx.spring.stream.TracingRecordListenerDecorator;
import org.kamranzafar.halyx.spring.stream.TracingStreamProducer;
import org.kamranzafar.halyx.stream.StreamProducer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Carries trace context through messages and stream records when the application uses Micrometer Tracing:
 * publishers and stream producers add it to attributes and headers, and {@code @HalyxListener} methods continue it. Disable with
 * {@code halyx.observations.enabled=false}.
 */
@AutoConfiguration(after = HalyxAutoConfiguration.class, afterName = {
        "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration" // Boot 4
})
@ConditionalOnClass({Tracer.class, Publisher.class})
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

    /** Wraps every {@code StreamProducer} bean; the tracer is looked up when the first producer is created. */
    @Bean
    public static BeanPostProcessor halyxTracingStreamProducerPostProcessor(ObjectProvider<Tracer> tracer,
                                                                            ObjectProvider<Propagator> propagator) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof StreamProducer producer && !(bean instanceof TracingStreamProducer)) {
                    Tracer t = tracer.getIfAvailable();
                    Propagator p = propagator.getIfAvailable();
                    if (t != null && p != null) {
                        return new TracingStreamProducer(producer, t, p);
                    }
                }
                return bean;
            }
        };
    }

    /** Wraps every {@code Publisher} bean; the tracer is looked up when the first publisher is created. */
    @Bean
    public static BeanPostProcessor halyxTracingPublisherPostProcessor(ObjectProvider<Tracer> tracer,
                                                                        ObjectProvider<Propagator> propagator) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof Publisher publisher && !(bean instanceof TracingPublisher)) {
                    Tracer t = tracer.getIfAvailable();
                    Propagator p = propagator.getIfAvailable();
                    if (t != null && p != null) {
                        return new TracingPublisher(publisher, t, p);
                    }
                }
                return bean;
            }
        };
    }
}
