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
package org.kamranzafar.cirrus.storage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Checksums of a blob's full content, base64-encoded as providers report them. Which algorithms are
 * present depends on the provider and on how the blob was written.
 */
public record Checksums(Map<ChecksumAlgorithm, String> values) {

    private static final Checksums NONE = new Checksums(Map.of());

    /** Preference order for verification: fastest widely-stored algorithm first. */
    private static final List<ChecksumAlgorithm> PREFERENCE =
            List.of(ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.MD5, ChecksumAlgorithm.CRC32);

    public Checksums {
        Objects.requireNonNull(values, "values");
        Map<ChecksumAlgorithm, String> copy = new EnumMap<>(ChecksumAlgorithm.class);
        values.forEach((algorithm, value) -> {
            if (value != null && !value.isEmpty()) {
                copy.put(algorithm, value);
            }
        });
        values = Map.copyOf(copy);
    }

    public static Checksums none() {
        return NONE;
    }

    /** Builds checksums from possibly-null provider values. */
    public static Checksums of(String crc32c, String md5, String crc32) {
        Map<ChecksumAlgorithm, String> values = new EnumMap<>(ChecksumAlgorithm.class);
        if (crc32c != null) {
            values.put(ChecksumAlgorithm.CRC32C, crc32c);
        }
        if (md5 != null) {
            values.put(ChecksumAlgorithm.MD5, md5);
        }
        if (crc32 != null) {
            values.put(ChecksumAlgorithm.CRC32, crc32);
        }
        return new Checksums(values);
    }

    public Optional<String> get(ChecksumAlgorithm algorithm) {
        return Optional.ofNullable(values.get(algorithm));
    }

    public Optional<String> crc32c() {
        return get(ChecksumAlgorithm.CRC32C);
    }

    public Optional<String> md5() {
        return get(ChecksumAlgorithm.MD5);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The algorithm to verify downloads with, if any is stored. */
    public Optional<ChecksumAlgorithm> preferred() {
        return PREFERENCE.stream().filter(values::containsKey).findFirst();
    }

    /** Algorithms present in both this and {@code other} whose values differ. */
    public List<ChecksumAlgorithm> mismatches(Checksums other) {
        List<ChecksumAlgorithm> mismatched = new ArrayList<>();
        values.forEach((algorithm, value) -> {
            String theirs = other.values.get(algorithm);
            if (theirs != null && !theirs.equals(value)) {
                mismatched.add(algorithm);
            }
        });
        return mismatched;
    }
}
