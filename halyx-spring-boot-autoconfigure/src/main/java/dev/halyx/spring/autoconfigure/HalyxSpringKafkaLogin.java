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
package dev.halyx.spring.autoconfigure;

import dev.halyx.Halyx;
import dev.halyx.error.UnsupportedFeatureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.Map;

/**
 * Adds a Halyx stream cluster's connection and login to every {@link DefaultKafkaProducerFactory} and
 * {@link DefaultKafkaConsumerFactory} bean, Boot's or the application's own. The properties are worked out once,
 * when the first factory is created, so the Halyx bean isn't created early.
 */
final class HalyxSpringKafkaLogin implements BeanPostProcessor {

    private final ObjectProvider<Halyx> halyx;
    private final ObjectProvider<HalyxProperties> properties;
    private final String cluster;
    private Map<String, Object> login;

    HalyxSpringKafkaLogin(ObjectProvider<Halyx> halyx, ObjectProvider<HalyxProperties> properties, String cluster) {
        this.halyx = halyx;
        this.properties = properties;
        this.cluster = cluster;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof DefaultKafkaProducerFactory<?, ?> producers) {
            producers.updateConfigs(login());
        } else if (bean instanceof DefaultKafkaConsumerFactory<?, ?> consumers) {
            consumers.updateConfigs(login());
        }
        return bean;
    }

    synchronized Map<String, Object> login() {
        if (login == null) {
            HalyxProperties config = properties.getObject();
            String name = BlobStoreBeanRegistrar.resolveCluster("halyx.spring-kafka", cluster.isBlank() ? null : cluster,
                    config.streamClusters());
            HalyxProperties.StreamClusterProperties clusterProperties = config.streamClusters().get(name);
            try {
                login = HalyxBlobStores.context(halyx.getObject(), "halyx.stream-clusters." + name, clusterProperties.provider())
                        .kafkaClientProperties(clusterProperties.toStreamConfig(name));
            } catch (UnsupportedFeatureException e) {
                throw new IllegalStateException("halyx.spring-kafka.cluster=" + name + ": " + e.getMessage()
                        + ". Spring for Apache Kafka needs a Kafka cluster, and the memory provider's streams run in "
                        + "process: in a profile without a cloud, leave halyx.spring-kafka.cluster out and set "
                        + "spring.kafka.bootstrap-servers", e);
            }
        }
        return login;
    }
}
