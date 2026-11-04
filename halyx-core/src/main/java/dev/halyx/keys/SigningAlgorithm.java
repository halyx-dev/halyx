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

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/**
 * The signing algorithms every provider offers. Signatures are as the algorithm defines them: PKCS #1 for RSA, and
 * DER-encoded ECDSA, so JWS and other formats can use them after their own conversion.
 */
public enum SigningAlgorithm {
    /** RSASSA-PSS with SHA-256, MGF1 with SHA-256 and a 32-byte salt. */
    RSA_PSS_SHA256(1, "SHA-256"),
    /** RSASSA-PKCS1-v1_5 with SHA-256. */
    RSA_PKCS1_SHA256(2, "SHA-256"),
    /** ECDSA on the P-256 curve with SHA-256. */
    ECDSA_P256_SHA256(3, "SHA-256"),
    /** ECDSA on the P-384 curve with SHA-384. */
    ECDSA_P384_SHA384(4, "SHA-384");

    private final int id;
    private final String digest;

    SigningAlgorithm(int id, String digest) {
        this.id = id;
        this.digest = digest;
    }

    /** The JDK name of the digest the algorithm signs, such as {@code SHA-256}. */
    public String digestAlgorithm() {
        return digest;
    }

    /** The digest's length in bytes. */
    public int digestLength() {
        return digest.equals("SHA-384") ? 48 : 32;
    }

    int id() {
        return id;
    }

    static SigningAlgorithm byId(int id) {
        for (SigningAlgorithm algorithm : values()) {
            if (algorithm.id == id) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown signing algorithm id " + id);
    }

    /** Whether {@code signature} over {@code data} verifies with {@code key}; false on any failure. */
    boolean verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            java.security.Signature verifier = switch (this) {
                case RSA_PSS_SHA256 -> {
                    var pss = java.security.Signature.getInstance("RSASSA-PSS");
                    pss.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                    yield pss;
                }
                case RSA_PKCS1_SHA256 -> java.security.Signature.getInstance("SHA256withRSA");
                case ECDSA_P256_SHA256 -> java.security.Signature.getInstance("SHA256withECDSA");
                case ECDSA_P384_SHA384 -> java.security.Signature.getInstance("SHA384withECDSA");
            };
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
