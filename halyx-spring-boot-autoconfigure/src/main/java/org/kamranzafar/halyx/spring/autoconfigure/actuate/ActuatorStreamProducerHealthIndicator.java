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
package org.kamranzafar.halyx.spring.autoconfigure.actuate;

import org.kamranzafar.halyx.stream.StreamProducer;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Spring Boot 3 variant of the stream producer health check: UP when the topic's partitions can be read from the cluster's metadata, which proves
 * connectivity, login and that the topic exists.
 */
public class ActuatorStreamProducerHealthIndicator extends AbstractHealthIndicator {

    private final StreamProducer producer;

    public ActuatorStreamProducerHealthIndicator(StreamProducer producer) {
        super("Stream producer health check failed");
        this.producer = producer;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", producer.provider()).withDetail("topic", producer.topic());
        builder.withDetail("partitions", producer.partitionCount());
        builder.up();
    }
}
