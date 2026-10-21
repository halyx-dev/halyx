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
package dev.halyx.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the stream TCK against the in-memory topics, which also checks the TCK itself. */
class InMemoryStreamConformanceTest extends StreamConformanceTest {

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Override
    protected CloudContext context() {
        return halyx.provider("test");
    }

    @Override
    protected StreamConfig.Builder cluster() {
        return StreamConfig.builder("memory:9092");
    }

    @Override
    protected void createTopic(String topic, int partitions) {
        // In-memory topics are created on first use, with PARTITIONS partitions.
    }

    @Override
    protected BlobStore claimCheckStore() {
        return halyx.provider("test").blobStore("claims");
    }

    /**
     * As in Kafka, a member that joins only gets a partition once its previous owner has revoked it, and so
     * committed what it processed: never while that owner still holds it, which would deliver records twice.
     */
    @Test
    void aJoiningMemberWaitsForThePreviousOwnerToGiveUpItsPartitions() {
        StreamConfig cluster = cluster().build();
        String topic = "handover-" + UUID.randomUUID();
        StreamProducer producer = context().streamProducer(StreamProducerConfig.of(cluster, topic));
        for (int i = 0; i < 9; i++) {
            producer.send("k" + i, "v" + i);
        }
        StreamConsumerConfig config = StreamConsumerConfig.builder(cluster, topic, "handover")
                .startPosition(StartPosition.EARLIEST).build();
        StreamConsumer first = context().streamConsumer(config);
        List<ReceivedRecord> read = new ArrayList<>();
        for (int polls = 0; read.size() < 9 && polls < 20; polls++) {
            read.addAll(first.poll(Duration.ofMillis(100)));
        }
        assertThat(read).hasSize(9);

        StreamConsumer second = context().streamConsumer(config);
        try {
            // The first member hasn't polled since, so it still owns every partition, uncommitted.
            assertThat(second.poll(Duration.ofMillis(300))).isEmpty();

            first.commit();
            first.close();
            // Its partitions are now the second member's, from the committed positions: nothing to read again.
            assertThat(second.poll(Duration.ofMillis(300))).isEmpty();

            producer.send("k0", "after");
            List<ReceivedRecord> next = new ArrayList<>();
            for (int polls = 0; next.isEmpty() && polls < 20; polls++) {
                next.addAll(second.poll(Duration.ofMillis(100)));
            }
            assertThat(next).extracting(ReceivedRecord::valueAsString).containsExactly("after");
        } finally {
            second.close();
        }
    }
}
