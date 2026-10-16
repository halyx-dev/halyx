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
package dev.halyx.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule;
import org.apache.kafka.common.security.plain.PlainLoginModule;
import org.apache.kafka.common.security.scram.ScramLoginModule;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Creates and owns the Kafka clients for one provider connection: one producer per cluster and settings,
 * shared by every topic sent to (a transactional producer each), and one consumer per stream consumer.
 * Providers call {@link #producer} and {@link #consumer} from {@code ProviderConnection} and close this with
 * the connection.
 *
 * <p>Client properties are applied in order: the provider's defaults (such as Event Hubs' recommended
 * timeouts), then Halyx' own (bootstrap servers, login, serializers, group and offsets), then {@code kafka.*}
 * properties of the cluster, then those of the producer or consumer, without the prefix. Later ones win.
 */
public final class KafkaStreamClients implements AutoCloseable {

    /** The client property carrying the {@link KafkaCredentialsSource} to {@link OAuthBearerLoginHandler}. */
    public static final String CREDENTIALS = "halyx.kafka.credentials";
    /** Prefix of properties passed to the Kafka clients, without it. */
    public static final String CLIENT_PREFIX = "kafka.";
    /** Kafka's default {@code max.request.size}. */
    static final long DEFAULT_MAX_REQUEST_SIZE = 1024 * 1024;
    /**
     * Kafka caches logins by {@code sasl.jaas.config}, which does not include the credentials object, so each
     * credentials source gets its own id in the JAAS options.
     */
    static final String LOGIN_OPTION = "halyxLogin";

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStreamClients.class);
    private static final AtomicLong LOGIN_IDS = new AtomicLong();

    private record ProducerKey(StreamConfig cluster, Map<String, String> properties, boolean idempotent) {
    }

    private final String provider;
    private final Map<ProducerKey, Producer<byte[], byte[]>> producers = new HashMap<>();
    private final List<AutoCloseable> owned = new ArrayList<>();

    public KafkaStreamClients(String provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * The login for {@code auth}: null for none and passwords, which need no credentials source; the
     * provider's token source for IAM, created only then.
     */
    public static KafkaCredentialsSource credentials(StreamAuth auth, Supplier<KafkaCredentialsSource> iamTokens) {
        return auth instanceof StreamAuth.Iam ? iamTokens.get() : null;
    }

    /** IAM token source for providers that do not offer one. */
    public static Supplier<KafkaCredentialsSource> iamUnsupported(String provider) {
        return () -> {
            throw new UnsupportedFeatureException("IAM stream authentication is not supported by " + provider,
                    null, provider, null);
        };
    }

    /**
     * A driver sending to {@code config.topic()}.
     *
     * @param credentials  the IAM token source, or null unless the cluster uses {@code StreamAuth.iam()}
     * @param capabilities what the provider's clusters support
     * @param defaults     the provider's client property defaults
     */
    public synchronized ProducerDriver producer(StreamProducerConfig config, KafkaCredentialsSource credentials,
                                                Capabilities capabilities, Map<String, String> defaults) {
        Map<String, Object> properties = clientProperties(config.cluster(), credentials, defaults);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        if (config.idempotent()) {
            properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            properties.put(ProducerConfig.ACKS_CONFIG, "all");
        }
        if (config.transactional()) {
            properties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "halyx-" + config.topic() + "-" + UUID.randomUUID());
        }
        properties.putAll(prefixed(config.properties()));
        keepOrderWithoutIdempotence(properties, config);
        long maxRecordBytes = maxRequestSize(properties);
        if (config.transactional()) {
            // Created on first use, after core has checked the TRANSACTIONS capability: initTransactions talks to
            // the cluster, and fails on clusters without transactions.
            return new KafkaProducerDriver(provider, config.topic(), () -> {
                KafkaProducer<byte[], byte[]> created = new KafkaProducer<>(properties);
                own(created);
                created.initTransactions();
                return created;
            }, true, maxRecordBytes, capabilities);
        }
        Producer<byte[], byte[]> shared = producers.computeIfAbsent(
                new ProducerKey(config.cluster(), config.properties(), config.idempotent()), k -> {
                    KafkaProducer<byte[], byte[]> created = new KafkaProducer<>(properties);
                    owned.add(created);
                    return created;
                });
        return new KafkaProducerDriver(provider, config.topic(), () -> shared, false, maxRecordBytes, capabilities);
    }

    /** A consumer group member reading {@code config.topic()}; closing the driver closes its consumer. */
    public synchronized ConsumerDriver consumer(StreamConsumerConfig config, KafkaCredentialsSource credentials,
                                                Capabilities capabilities, Map<String, String> defaults) {
        Map<String, Object> properties = clientProperties(config.cluster(), credentials, defaults);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.group());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.startPosition().name().toLowerCase(Locale.ROOT));
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, config.readCommitted() ? "read_committed" : "read_uncommitted");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.putAll(prefixed(config.properties()));
        KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties);
        KafkaConsumerDriver driver = new KafkaConsumerDriver(provider, config.topic(), config.group(), consumer, capabilities);
        owned.add(driver);
        return driver;
    }

    private synchronized void own(AutoCloseable client) {
        owned.add(client);
    }

    /**
     * Kafka client properties for a cluster: bootstrap servers and login, with the provider's defaults beneath and
     * the cluster's {@code kafka.*} properties on top. For IAM login the map holds the credentials source itself,
     * which {@link OAuthBearerLoginHandler} reads, so pass it to the clients unchanged.
     *
     * @param credentials the IAM token source, or null unless the cluster uses {@code StreamAuth.iam()}
     */
    public static Map<String, Object> clientProperties(StreamConfig cluster, KafkaCredentialsSource credentials,
                                                Map<String, String> defaults) {
        Map<String, Object> properties = new LinkedHashMap<>(defaults);
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
        switch (cluster.auth()) {
            case StreamAuth.None ignored -> {
            }
            case StreamAuth.Password password -> {
                properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
                properties.put(SaslConfigs.SASL_MECHANISM, password.mechanism().kafkaName());
                properties.put(SaslConfigs.SASL_JAAS_CONFIG, jaas(loginModule(password.mechanism()),
                        Map.of("username", password.username(), "password", password.password())));
            }
            case StreamAuth.Iam ignored -> {
                Objects.requireNonNull(credentials, "IAM login needs a credentials source");
                properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
                properties.put(SaslConfigs.SASL_MECHANISM, OAuthBearerLoginHandler.MECHANISM);
                properties.put(SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS, OAuthBearerLoginHandler.class.getName());
                properties.put(SaslConfigs.SASL_JAAS_CONFIG, jaas(OAuthBearerLoginModule.class.getName(),
                        Map.of(LOGIN_OPTION, Long.toString(LOGIN_IDS.incrementAndGet()))));
                properties.put(CREDENTIALS, credentials);
            }
        }
        properties.putAll(prefixed(cluster.properties()));
        return properties;
    }

    /**
     * Without idempotence, a batch retried after an error can land behind batches sent after it, which breaks the
     * order of records with the same key. One request in flight per connection keeps it, unless the application
     * chose a value itself. Event Hubs, where idempotence is off, reordered records this way.
     */
    static void keepOrderWithoutIdempotence(Map<String, Object> properties, StreamProducerConfig config) {
        boolean idempotent = !"false".equalsIgnoreCase(String.valueOf(
                properties.getOrDefault(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")));
        String inFlight = CLIENT_PREFIX + ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION;
        boolean chosen = config.properties().containsKey(inFlight) || config.cluster().properties().containsKey(inFlight);
        if (!idempotent && !chosen) {
            properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
        }
    }

    private static String loginModule(SaslMechanism mechanism) {
        return switch (mechanism) {
            case PLAIN -> PlainLoginModule.class.getName();
            case SCRAM_SHA_256, SCRAM_SHA_512 -> ScramLoginModule.class.getName();
        };
    }

    /** A {@code sasl.jaas.config} entry; option values are quoted, with quotes and backslashes escaped. */
    static String jaas(String loginModule, Map<String, String> options) {
        StringBuilder jaas = new StringBuilder(loginModule).append(" required");
        new TreeMap<>(options).forEach((key, value) -> jaas.append(' ').append(key).append("=\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"'));
        return jaas.append(';').toString();
    }

    private static Map<String, String> prefixed(Map<String, String> properties) {
        Map<String, String> client = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            if (key.startsWith(CLIENT_PREFIX)) {
                client.put(key.substring(CLIENT_PREFIX.length()), value);
            }
        });
        return client;
    }

    private static long maxRequestSize(Map<String, Object> properties) {
        Object configured = properties.get(ProducerConfig.MAX_REQUEST_SIZE_CONFIG);
        return configured == null ? DEFAULT_MAX_REQUEST_SIZE : Long.parseLong(configured.toString());
    }

    @Override
    public synchronized void close() {
        for (AutoCloseable client : owned) {
            try {
                if (client instanceof Producer<?, ?> producer) {
                    producer.close(Duration.ofSeconds(30));
                } else {
                    client.close();
                }
            } catch (Exception e) {
                LOG.warn("Could not close Kafka client {} cleanly", client, e);
            }
        }
        owned.clear();
        producers.clear();
    }
}
