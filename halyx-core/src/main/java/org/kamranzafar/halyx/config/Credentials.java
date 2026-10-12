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
package org.kamranzafar.halyx.config;

import java.util.Objects;

/**
 * How a provider authenticates. Each provider maps these onto its native credential chain.
 *
 * <ul>
 *   <li>{@link DefaultChain}: the platform default (AWS default chain, {@code DefaultAzureCredential},
 *       Google Application Default Credentials). This is the right choice when running on the cloud.</li>
 *   <li>{@link Static}: an explicit id/secret pair (AWS access key, Azure storage account name and key).</li>
 *   <li>{@link Anonymous}: no credentials, for public resources and local emulators that do not check auth.</li>
 * </ul>
 */
public sealed interface Credentials {

    static Credentials defaultChain() {
        return DefaultChain.INSTANCE;
    }

    static Credentials of(String id, String secret) {
        return new Static(id, secret);
    }

    static Credentials anonymous() {
        return Anonymous.INSTANCE;
    }

    record DefaultChain() implements Credentials {
        static final DefaultChain INSTANCE = new DefaultChain();
    }

    record Static(String id, String secret) implements Credentials {
        public Static {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(secret, "secret");
        }

        @Override
        public String toString() {
            return "Static[id=" + id + ", secret=****]";
        }
    }

    record Anonymous() implements Credentials {
        static final Anonymous INSTANCE = new Anonymous();
    }
}
