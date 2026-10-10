package org.kamranzafar.cirrus.spring.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method for every message on a {@code cirrus.subscriptions.<name>} subscription:
 *
 * <pre>{@code
 * @CirrusListener("orders")
 * void onOrder(ReceivedMessage message) { ... }   // or (String body), or (byte[] body)
 * }</pre>
 *
 * Returning normally acks the message; throwing nacks it for redelivery with backoff. Consumers start once
 * the application is ready and stop, letting running handlers finish, before it shuts down. Delivery is at
 * least once: make the method idempotent.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CirrusListener {

    /** The bean name of the subscription, a key under {@code cirrus.subscriptions}. */
    String value();

    /** Messages handled at once; overrides {@code cirrus.subscriptions.<name>.listener.concurrency} when positive. */
    int concurrency() default -1;
}
