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
package dev.halyx.azure;

import com.azure.core.credential.AzureKeyCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps key credentials up to date with keys given as suppliers: asks each supplier every
 * {@link AzureProperties#KEY_REFRESH_INTERVAL} and updates its credential when the key changed. Cosmos DB and Event
 * Grid read their credential on every request, so a rotated key takes effect without a restart. A failed refresh
 * keeps the current key and is tried again at the next interval.
 */
final class KeyRefresher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KeyRefresher.class);

    private final Duration interval;
    private final List<Key> keys = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    KeyRefresher(Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException(AzureProperties.KEY_REFRESH_INTERVAL + " must be positive");
        }
        this.interval = interval;
    }

    /** A credential with the supplier's key, read now so a missing one fails here, and refreshed from then on. */
    synchronized AzureKeyCredential credential(String name, Supplier<String> key) {
        AzureKeyCredential credential = new AzureKeyCredential(key.get());
        keys.add(new Key(name, key, credential));
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("halyx-azure-key-refresh").factory());
            scheduler.scheduleWithFixedDelay(this::refresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
        return credential;
    }

    /** Asks every supplier once, updating the credentials whose key changed. */
    void refresh() {
        for (Key key : keys) {
            try {
                String current = key.supplier().get();
                if (current != null && !current.isBlank() && !current.equals(key.credential().getKey())) {
                    key.credential().update(current);
                    LOG.info("Updated the {} after a rotation", key.name());
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not refresh the {}; keeping the current one: {}", key.name(), e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        keys.clear();
    }

    private record Key(String name, Supplier<String> supplier, AzureKeyCredential credential) {
    }
}
