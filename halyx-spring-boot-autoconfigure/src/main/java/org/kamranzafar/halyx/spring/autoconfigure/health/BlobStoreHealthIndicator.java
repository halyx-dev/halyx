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
package org.kamranzafar.halyx.spring.autoconfigure.health;

import org.kamranzafar.halyx.storage.BlobStore;
import org.kamranzafar.halyx.storage.ListOptions;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Spring Boot 4 variant of the blob store health check: UP when the bucket can be listed, which
 * proves connectivity, credentials and that the bucket exists.
 */
public class BlobStoreHealthIndicator extends AbstractHealthIndicator {

    private static final ListOptions PROBE = ListOptions.builder().pageSize(1).build();

    private final BlobStore store;

    public BlobStoreHealthIndicator(BlobStore store) {
        super("Blob store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("provider", store.provider()).withDetail("bucket", store.bucket());
        store.list(PROBE);
        builder.up();
    }
}
