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

import java.util.Optional;

/**
 * How a data key was wrapped, as recorded in each key entry of the HLXS format. The ids are a registry that never
 * changes once released: new algorithms are only added, and 240 to 255 are for private use.
 */
public enum WrapAlgorithm {
    /** AWS KMS {@code Encrypt} with a symmetric key. */
    AWS_KMS(1),
    /** Azure Key Vault {@code wrapKey} with {@code RSA-OAEP-256}. */
    AZURE_RSA_OAEP_256(2),
    /** Azure Key Vault {@code wrapKey} with {@code A256KW}, on Managed HSM. Reserved: not used yet. */
    AZURE_A256KW(3),
    /** Google Cloud KMS {@code Encrypt} with a symmetric key. */
    GCP_KMS(4),
    /** HashiCorp Vault Transit {@code encrypt}. */
    VAULT_TRANSIT(5),
    /** The memory provider's AES-256-GCM, for tests and local development. */
    MEMORY(6);

    private final int id;

    WrapAlgorithm(int id) {
        this.id = id;
    }

    /** The registry id written in the envelope. */
    public int id() {
        return id;
    }

    /** The algorithm with registry id {@code id}, or empty for an id this version doesn't know. */
    public static Optional<WrapAlgorithm> byId(int id) {
        for (WrapAlgorithm algorithm : values()) {
            if (algorithm.id == id) {
                return Optional.of(algorithm);
            }
        }
        return Optional.empty();
    }
}
