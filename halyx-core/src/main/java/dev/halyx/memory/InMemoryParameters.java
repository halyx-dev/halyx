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
package dev.halyx.memory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * What the memory provider stores for one parameter store, beyond what the portable API reports, so tests can check
 * it. Reached with {@code parameterStore.unwrap(InMemoryParameters.class)}:
 *
 * <pre>{@code
 * assertThat(store.unwrap(InMemoryParameters.class).serverSideKey("orders/db/password"))
 *         .contains(cryptoKey.metadata().nativeId());
 * }</pre>
 */
public final class InMemoryParameters {

    private final ConcurrentSkipListMap<String, InMemoryParameterDriver.Entry> values;

    InMemoryParameters(ConcurrentSkipListMap<String, InMemoryParameterDriver.Entry> values) {
        this.values = values;
    }

    /**
     * The native id of the server-side key the parameter was created with; empty for the service's default encryption,
     * or when there is no such parameter.
     *
     * @param name the full name, the store's prefix included
     */
    public Optional<String> serverSideKey(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(values.get(name)).map(InMemoryParameterDriver.Entry::serverSideKey);
    }
}
