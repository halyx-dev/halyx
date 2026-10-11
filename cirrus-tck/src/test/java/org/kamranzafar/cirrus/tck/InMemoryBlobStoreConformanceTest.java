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
import org.kamranzafar.cirrus.storage.BlobStore;

/** Runs the TCK against the in-memory provider, which also checks the TCK itself. */
class InMemoryBlobStoreConformanceTest extends BlobStoreConformanceTest {

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
    protected BlobStore blobStore() {
        return cirrus.provider("test").blobStore("tck");
    }
}
