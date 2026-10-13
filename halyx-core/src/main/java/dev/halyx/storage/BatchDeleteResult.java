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
package dev.halyx.storage;

import dev.halyx.error.CloudException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a batch delete. Keys that did not exist count as deleted, as with a single delete. Both
 * collections keep the order of the requested keys.
 *
 * @param deleted keys that are now absent
 * @param failed  keys that could not be deleted, with the reason
 */
public record BatchDeleteResult(List<BlobKey> deleted, Map<BlobKey, CloudException> failed) {

    public BatchDeleteResult {
        deleted = List.copyOf(deleted);
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
    }

    public boolean isComplete() {
        return failed.isEmpty();
    }

    /** Combines results of several provider batches. */
    public static BatchDeleteResult merge(List<BatchDeleteResult> results) {
        List<BlobKey> deleted = new ArrayList<>();
        Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
        for (BatchDeleteResult result : results) {
            deleted.addAll(result.deleted());
            failed.putAll(result.failed());
        }
        return new BatchDeleteResult(deleted, failed);
    }
}
