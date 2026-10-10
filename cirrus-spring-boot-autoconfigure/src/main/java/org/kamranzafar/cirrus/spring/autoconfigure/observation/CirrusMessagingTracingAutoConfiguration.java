package org.kamranzafar.cirrus.spring.autoconfigure.observation;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.kamranzafar.cirrus.messaging.Publisher;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusAutoConfiguration;
import org.kamranzafar.cirrus.spring.messaging.MessageListenerDecorator;
import org.kamranzafar.cirrus.spring.messaging.TracingListenerDecorator;
import org.kamranzafar.cirrus.spring.messaging.TracingPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Carries trace context through messages when the application uses Micrometer Tracing: publishers add it to
 * message attributes and {@code @CirrusListener} methods continue it. Disable with
 * {@code cirrus.observations.enabled=false}.
 */
@AutoConfiguration(after = CirrusAutoConfiguration.class, afterName = {
        "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration" // Boot 4
})
@ConditionalOnClass({Tracer.class, Publisher.class})
@ConditionalOnBean({Tracer.class, Propagator.class})
@ConditionalOnProperty(prefix = "cirrus.observations", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CirrusMessagingTracingAutoConfiguration {

    @Bean
    public MessageListenerDecorator cirrusTracingListenerDecorator(Tracer tracer, Propagator propagator) {
        return new TracingListenerDecorator(tracer, propagator);
    }

    /** Wraps every {@code Publisher} bean; the tracer is looked up when the first publisher is created. */
    @Bean
    public static BeanPostProcessor cirrusTracingPublisherPostProcessor(ObjectProvider<Tracer> tracer,
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
