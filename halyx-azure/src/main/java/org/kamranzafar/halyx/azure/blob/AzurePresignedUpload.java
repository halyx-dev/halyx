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
package org.kamranzafar.halyx.azure.blob;

import org.kamranzafar.halyx.azure.AzureProvider;
import org.kamranzafar.halyx.error.InvalidArgumentException;
import org.kamranzafar.halyx.json.Json;
import org.kamranzafar.halyx.storage.BlobHeaders;
import org.kamranzafar.halyx.storage.PutOptions;
import org.kamranzafar.halyx.storage.StorageTier;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Azure has no server-side upload session: parts are staged blocks, committed with Put Block List. The
 * "upload id" is therefore a token holding what the commit needs (the blob's headers, metadata and tier)
 * plus a random nonce that keeps this upload's block ids apart from any other upload to the same blob.
 */
record AzurePresignedUpload(String nonce, BlobHeaders headers, Map<String, String> metadata,
                            Optional<StorageTier> tier) {

    private static final SecureRandom RANDOM = new SecureRandom();

    static AzurePresignedUpload start(PutOptions options) {
        byte[] nonce = new byte[8];
        RANDOM.nextBytes(nonce);
        return new AzurePresignedUpload(HexFormat.of().formatHex(nonce), options.headers(), options.metadata(),
                options.tier());
    }

    /** Block ids are base64 and must all have the same length within a blob. */
    String blockId(int partNumber) {
        return Base64.getEncoder().encodeToString(
                (nonce + "-" + String.format("%05d", partNumber)).getBytes(StandardCharsets.US_ASCII));
    }

    String encode() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v", 1);
        json.put("n", nonce);
        headers.contentType().ifPresent(v -> json.put("ct", v));
        headers.contentEncoding().ifPresent(v -> json.put("ce", v));
        headers.contentDisposition().ifPresent(v -> json.put("cd", v));
        headers.contentLanguage().ifPresent(v -> json.put("cl", v));
        headers.cacheControl().ifPresent(v -> json.put("cc", v));
        if (!metadata.isEmpty()) {
            json.put("m", metadata);
        }
        tier.ifPresent(t -> json.put("t", t.name()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(json).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static AzurePresignedUpload decode(String uploadId) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(uploadId), StandardCharsets.UTF_8);
            Map<String, Object> json = Json.parseObject(text).orElseThrow(IllegalArgumentException::new);
            String nonce = (String) json.get("n");
            if (nonce == null || !nonce.matches("[0-9a-f]{16}")) {
                throw new IllegalArgumentException();
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            if (json.get("m") instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            BlobHeaders headers = BlobHeaders.of((String) json.get("ct"), (String) json.get("ce"),
                    (String) json.get("cd"), (String) json.get("cl"), (String) json.get("cc"));
            Optional<StorageTier> tier = Optional.ofNullable((String) json.get("t")).map(StorageTier::valueOf);
            return new AzurePresignedUpload(nonce, headers, metadata, tier);
        } catch (RuntimeException e) {
            throw new InvalidArgumentException("Not an upload id from startPresignedUpload: " + uploadId, e,
                    AzureProvider.ID, null);
        }
    }
}
