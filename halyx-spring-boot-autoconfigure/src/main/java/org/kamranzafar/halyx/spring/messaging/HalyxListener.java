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
package org.kamranzafar.halyx.spring.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method for every message on a {@code halyx.subscriptions.<name>} subscription:
 *
 * <pre>{@code
 * @HalyxListener("orders")
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
public @interface HalyxListener {

    /** The bean name of the subscription, a key under {@code halyx.subscriptions}. */
    String value();

    /** Messages handled at once; overrides {@code halyx.subscriptions.<name>.listener.concurrency} when positive. */
    int concurrency() default -1;
}
