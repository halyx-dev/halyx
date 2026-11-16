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
package dev.halyx.samples.orders.e2e;

import dev.halyx.CloudContext;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.TableConfig;
import dev.halyx.storage.BlobKey;

/**
 * What the stores hold, read past the service's encryption through plain stores on the service's own provider, for
 * deployments the tests run in process. Uses the sample's default resource names.
 */
record StoredData(CloudContext cloud) {

    /** The receipt blob's bytes as stored. */
    byte[] receipt(String customerId, String orderId) {
        return cloud.blobStore("portable-orders-receipts")
                .getBytes(BlobKey.of("receipts/" + customerId + "/" + orderId + ".txt"));
    }

    /** The {@code email} field of the order's contact item as stored. */
    Object contactEmail(String customerId, String orderId) {
        return cloud.documentStore(TableConfig.builder("portable-orders").sortKey("sk").build())
                .get(Key.of("contact#" + customerId, orderId))
                .orElseThrow(() -> new AssertionError("no contact item for order " + orderId))
                .document().get("email");
    }
}
