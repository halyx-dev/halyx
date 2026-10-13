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
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConfig;

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
}
