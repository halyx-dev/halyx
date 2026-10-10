package org.kamranzafar.cirrus.storage;

import org.kamranzafar.cirrus.error.CloudException;

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
