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
package dev.halyx.stream;

import dev.halyx.capability.Capability;

/** Optional stream features. Asking for one the provider doesn't support fails rather than being ignored. */
public enum StreamCapability implements Capability {
    /**
     * {@link StreamProducerConfig.Builder#idempotent(boolean)}: the broker drops duplicates of a record that the
     * producer resent after a failure, per partition.
     */
    IDEMPOTENCE,
    /**
     * {@link StreamProducerConfig.Builder#transactional(boolean)}: {@code sendAll} commits all of its records or
     * none, as consumers with {@code readCommitted} see them.
     */
    TRANSACTIONS
}
