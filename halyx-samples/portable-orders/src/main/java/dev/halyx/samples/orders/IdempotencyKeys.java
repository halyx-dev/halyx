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
package dev.halyx.samples.orders;

import dev.halyx.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The {@code cache} feature: an {@code Idempotency-Key} header makes placing an order safe to retry. The first request
 * claims the key in the {@code idempotency} cache for a day; a retry finds the order it placed.
 */
@Component
@Profile("cache")
class IdempotencyKeys {

    private static final Duration RETENTION = Duration.ofDays(1);

    private final Cache<String> keys;

    IdempotencyKeys(@Qualifier("idempotency") Cache<String> keys) {
        this.keys = keys;
    }

    /** Claims the key for a new order; if an earlier request holds it, returns that request's order id. */
    Optional<String> claim(String customerId, String idempotencyKey, String orderId) {
        String key = key(customerId, idempotencyKey);
        if (keys.putIfAbsent(key, orderId, RETENTION)) {
            return Optional.empty();
        }
        return Optional.of(keys.get(key).orElseThrow(() -> new OrderConflictException(
                "idempotency key " + idempotencyKey + " expired while it was being checked; retry")));
    }

    /** Frees a key whose order couldn't be stored, so a retry places it. */
    void release(String customerId, String idempotencyKey) {
        keys.delete(key(customerId, idempotencyKey));
    }

    private static String key(String customerId, String idempotencyKey) {
        return customerId + "/" + idempotencyKey;
    }
}
