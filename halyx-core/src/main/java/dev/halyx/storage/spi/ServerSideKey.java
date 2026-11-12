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
package dev.halyx.storage.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The key a driver asks the storage service to encrypt with, resolved by core from a {@code CryptoKey} of the same
 * provider. Drivers that declare {@code SERVER_SIDE_KMS} apply it through {@link BlobStoreDriver#withServerSideKey}.
 *
 * @param nativeId   the key service's own id of the key: an AWS key ARN, a Cloud KMS crypto key name, an Azure Key
 *                   Vault key URL
 * @param properties the key's options, such as {@code azure.storage.encryption-scope}
 */
public record ServerSideKey(String nativeId, Map<String, String> properties) {

    public ServerSideKey {
        Objects.requireNonNull(nativeId, "nativeId");
        properties = Map.copyOf(properties);
    }

    /** A property of the key's options. */
    public Optional<String> property(String name) {
        return Optional.ofNullable(properties.get(name));
    }
}
