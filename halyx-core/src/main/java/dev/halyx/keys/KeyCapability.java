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
package dev.halyx.keys;

import dev.halyx.capability.Capability;

/** Key management features that differ between providers or keys. */
public enum KeyCapability implements Capability {
    /** The key can sign: a signing key, or an Azure RSA or EC key, which can also wrap. */
    SIGN,
    /**
     * HMAC with a key held by the key service ({@link CryptoKey#mac}): AWS KMS, Google Cloud KMS and Vault Transit.
     * Azure Key Vault offers it only on Managed HSM, which Halyx doesn't support yet.
     */
    MAC,
    /**
     * {@link CryptoKey#rewrap} within one store never exposes the data key outside the key service (AWS
     * {@code ReEncrypt}, Vault {@code rewrap}). Elsewhere rewrap unwraps and wraps, with the same result.
     */
    SERVER_SIDE_REWRAP
}
