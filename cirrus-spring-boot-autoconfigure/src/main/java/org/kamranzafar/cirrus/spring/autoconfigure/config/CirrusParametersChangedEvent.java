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
package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * Published when imported parameters changed and the {@code Environment} was updated. Listen for it to
 * re-read values (for example, to rebuild a connection pool after a password rotation).
 */
public class CirrusParametersChangedEvent extends ApplicationEvent {

    private final String store;
    private final Set<String> keys;

    public CirrusParametersChangedEvent(Object source, String store, Set<String> keys) {
        super(source);
        this.store = store;
        this.keys = Set.copyOf(keys);
    }

    /** The {@code cirrus.parameter-stores} name of the store that changed. */
    public String store() {
        return store;
    }

    /** Property keys that were added, changed or removed. */
    public Set<String> keys() {
        return keys;
    }
}
