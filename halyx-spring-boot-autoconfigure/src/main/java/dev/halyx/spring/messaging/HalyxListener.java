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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method for every message on a {@code halyx.subscriptions.<name>} subscription, or every
 * record on a {@code halyx.stream-consumers.<name>} stream consumer:
 *
 * <pre>{@code
 * @HalyxListener("orders")
 * void onOrder(ReceivedMessage message) { ... }   // or (String body), or (byte[] body)
 *
 * @HalyxListener("order-events")
 * void onEvent(ReceivedRecord record) { ... }     // or (String value), or (byte[] value)
 * }</pre>
 *
 * For a subscription, returning normally acks the message and throwing nacks it for redelivery with backoff.
 * For a stream consumer, returning normally commits the record and throwing retries it with backoff, holding
 * back the rest of its partition. Consumers start once
 * the application is ready and stop, letting running handlers finish, before it shuts down. Delivery is at
 * least once: make the method idempotent.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HalyxListener {

    /** The bean name of the subscription or stream consumer: a key under {@code halyx.subscriptions} or {@code halyx.stream-consumers}. */
    String value();

    /**
     * Messages handled at once; overrides {@code halyx.subscriptions.<name>.listener.concurrency} when positive.
     * Subscriptions only: setting it on a stream consumer fails at startup, since a stream consumer handles each
     * partition in order.
     */
    int concurrency() default -1;
}
