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
package dev.halyx.spring.keys;

import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.BlobSummary;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim checks whose {@code blob-store} is a store with an {@code encryption-key}, and the same claim checks over a
 * plain store, on Boot 3.5 and 4. {@code raw} is the same bucket without the key, to see what is stored.
 */
class HalyxEncryptedClaimChecksTest {

    private static final String BODY = "{\"card\":\"4111111111111111\",\"note\":\"longer than the threshold\"}";

    private static final String[] CHANNELS = {
            "halyx.providers.local.type=memory",
            "halyx.blob-stores.raw.bucket=claims",
            "halyx.topics.orders-out.name=orders",
            "halyx.topics.orders-out.claim-check.blob-store=claims",
            "halyx.topics.orders-out.claim-check.threshold=10",
            "halyx.subscriptions.orders.name=orders-sub",
            "halyx.subscriptions.orders.topic=orders",
            "halyx.subscriptions.orders.claim-check.blob-store=claims",
            "halyx.subscriptions.orders.claim-check.threshold=10",
            "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
            "halyx.stream-producers.events-out.topic=events",
            "halyx.stream-producers.events-out.claim-check.blob-store=claims",
            "halyx.stream-producers.events-out.claim-check.threshold=10",
            "halyx.stream-producers.events-out.claim-check.prefix=stream-claims/",
            "halyx.stream-consumers.events.topic=events",
            "halyx.stream-consumers.events.group=billing",
            "halyx.stream-consumers.events.start-position=earliest",
            "halyx.stream-consumers.events.claim-check.blob-store=claims",
            "halyx.stream-consumers.events.claim-check.threshold=10",
            "halyx.stream-consumers.events.claim-check.prefix=stream-claims/"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues(CHANNELS)
            .withBean(BeanPostProcessor.class, HalyxEncryptedClaimChecksTest::memoryKeys);

    /** Creates the memory key {@code claims} in each key store as it is made. */
    private static BeanPostProcessor memoryKeys() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof KeyStore store) {
                    store.unwrap(InMemoryKeys.class).create("claims", KeyPurpose.ENCRYPTION);
                }
                return bean;
            }
        };
    }

    @Test
    void anEncryptedBlobStoreSealsClaimCheckedBodies() {
        runner.withPropertyValues(
                "halyx.blob-stores.claims.encryption-key.name=claims",
                "halyx.key-stores.kms.location=tests").run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(publishAndReceive(context)).isEqualTo(BODY);
            assertThat(sendAndPoll(context)).isEqualTo(BODY);

            assertThat(stored(context, "claim-check/")).startsWith("HLXS").doesNotContain("4111");
            assertThat(stored(context, "stream-claims/")).startsWith("HLXS").doesNotContain("4111");
            KeyStore kms = context.getBean("kms", KeyStore.class);
            assertThat(kms.statistics().wraps()).as("one per offloaded body").isEqualTo(2);
            assertThat(kms.statistics().unwraps()).as("one per delivery").isEqualTo(2);
        });
    }

    @Test
    void aPlainBlobStoreKeepsClaimChecksAsTheyWere() {
        runner.withPropertyValues("halyx.blob-stores.claims.bucket=claims").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(KeyStore.class)).as("no key store is opened").isEmpty();

            assertThat(publishAndReceive(context)).isEqualTo(BODY);
            assertThat(sendAndPoll(context)).isEqualTo(BODY);

            assertThat(stored(context, "claim-check/")).isEqualTo(BODY);
            assertThat(stored(context, "stream-claims/")).isEqualTo(BODY);
        });
    }

    private static String publishAndReceive(ApplicationContext context) {
        context.getBean("orders-out", Publisher.class).publish(BODY);
        List<ReceivedMessage> received = context.getBean("orders", Subscription.class)
                .receive(1, Duration.ofSeconds(5));
        assertThat(received).hasSize(1);
        received.getFirst().ack();
        return received.getFirst().bodyAsString();
    }

    private static String sendAndPoll(ApplicationContext context) {
        context.getBean("events-out", StreamProducer.class).send(OutgoingRecord.of("o-1", BODY));
        StreamConsumer consumer = context.getBean("events", StreamConsumer.class);
        List<ReceivedRecord> records = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(15);
        while (records.isEmpty() && Instant.now().isBefore(deadline)) {
            records.addAll(consumer.poll(Duration.ofMillis(200)));
        }
        assertThat(records).hasSize(1);
        return records.getFirst().valueAsString();
    }

    /** The one body stored under {@code prefix}, as the bucket holds it. */
    private static String stored(ApplicationContext context, String prefix) {
        BlobStore raw = context.getBean("raw", BlobStore.class);
        List<BlobSummary> blobs = raw.listAll(prefix).toList();
        assertThat(blobs).hasSize(1);
        return new String(raw.getBytes(blobs.getFirst().key()), StandardCharsets.ISO_8859_1);
    }
}
