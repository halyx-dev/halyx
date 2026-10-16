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
package dev.halyx.events;

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.events.spi.EventPublisherDriver;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** The portable event publisher: size checks and batching over a driver, through the interceptors. */
public final class DefaultEventPublisher implements EventPublisher {

    static final String SERVICE = "events";

    private final EventPublisherDriver driver;
    private final InterceptorChain interceptors;

    public DefaultEventPublisher(EventPublisherDriver driver, InterceptorChain interceptors) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public String bus() {
        return driver.bus();
    }

    @Override
    public void publish(CloudEvent event) {
        publishAll(List.of(Objects.requireNonNull(event, "event")));
    }

    @Override
    public void publishAll(List<CloudEvent> events) {
        Objects.requireNonNull(events, "events");
        for (CloudEvent event : events) {
            long size = CloudEvents.toJson(Objects.requireNonNull(event, "event")).getBytes(StandardCharsets.UTF_8).length;
            if (size > driver.maxEventBytes()) {
                throw new InvalidArgumentException("Event " + event.id() + " is " + size + " bytes as CloudEvents JSON, over the "
                        + driver.maxEventBytes() + "-byte limit of " + provider(), null, provider(), null);
            }
        }
        int size = Math.max(1, driver.maxBatchSize());
        for (int from = 0; from < events.size(); from += size) {
            List<CloudEvent> batch = events.subList(from, Math.min(from + size, events.size()));
            // Events keep their ids, so a retried publish is safe for receivers that deduplicate.
            OperationContext context = new OperationContext(SERVICE, "publish", provider(), bus(), true);
            interceptors.execute(context, () -> {
                try {
                    driver.publish(batch);
                    return null;
                } catch (RuntimeException e) {
                    throw driver.translate(e);
                }
            });
        }
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "EventPublisher[" + provider() + ":" + bus() + "]";
    }
}
