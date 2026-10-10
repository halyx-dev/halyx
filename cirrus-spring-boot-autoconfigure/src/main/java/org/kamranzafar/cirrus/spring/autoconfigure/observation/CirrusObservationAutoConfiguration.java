package org.kamranzafar.cirrus.spring.autoconfigure.observation;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Observes Cirrus operations when the application has a Micrometer {@link ObservationRegistry}. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration", // Boot 3
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration" // Boot 4
})
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "cirrus.observations", name = "enabled", matchIfMissing = true)
public class CirrusObservationAutoConfiguration {

    /** Outermost interceptor, so the observation covers retries and every other interceptor. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    public ObservationInterceptor cirrusObservationInterceptor(ObservationRegistry registry) {
        return new ObservationInterceptor(registry);
    }
}
