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
package org.kamranzafar.cirrus.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.messaging.ClaimCheck;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.storage.BlobStore;

/** Runs the messaging TCK against the in-memory broker, which also checks the TCK itself. */
class InMemoryMessagingConformanceTest extends MessagingConformanceTest {

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("test", ProviderConfig.of("memory")).build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
    }

    @Override
    protected Channel channel(String name, ClaimCheck claimCheck) {
        TopicConfig.Builder topic = TopicConfig.builder(name);
        SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(name + "-sub").topic(name)
                .property("memory.lease", "PT1S");
        if (claimCheck != null) {
            topic.claimCheck(claimCheck);
            subscription.claimCheck(claimCheck);
        }
        var context = cirrus.provider("test");
        // Subscribe before publishing, as with a real topic.
        var sub = context.subscription(subscription.build());
        return new Channel(context.publisher(topic.build()), sub);
    }

    @Override
    protected BlobStore claimCheckStore() {
        return cirrus.provider("test").blobStore("claims");
    }
}
