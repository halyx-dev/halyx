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
package dev.halyx.parameters;

/**
 * A parameter store that seals every value with a key before it leaves the application, and opens it on the way back:
 * the view {@link ParameterStore#encryptedWith} returns. Each value is stored as HLXS text ({@code hlxs1:...}) bound to
 * the parameter's full name, so a value can't be moved to another name unnoticed, while any store on any cloud that
 * holds it under the same name can open it with the key.
 *
 * <p>Reads, and what {@code put} returns, carry the plaintext; the read cache ({@link ParameterStoreConfig#cacheTtl()})
 * holds it, as a plain store's does: a cached read makes no key service call. Without a cache, every read unwraps
 * once. A value stored in the clear is refused unless {@link #allowPlaintext()} is set; a put whose sealed value is
 * longer than the backend stores is refused before it is sent.
 */
public interface EncryptedParameterStore extends ParameterStore {

    /**
     * This view, also reading values stored in the clear, as they are: for moving a store to encryption one value at a
     * time. Writes are still sealed. A value that starts with {@code hlxs1:} is always opened, never returned as it is.
     */
    EncryptedParameterStore allowPlaintext();
}
