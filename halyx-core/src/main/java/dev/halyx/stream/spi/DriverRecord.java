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
package dev.halyx.stream.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A record as a consumer driver read it.
 *
 * @param partition the partition it was read from
 * @param offset    its offset in the partition
 * @param key       the key as UTF-8 text, or null
 * @param value     the value; empty for a record without one
 * @param headers   string headers (the last value of a repeated header)
 * @param timestamp the record's timestamp, or null if unknown
 */
public record DriverRecord(Partition partition, long offset, String key, byte[] value, Map<String, String> headers,
                           Instant timestamp) {

    public DriverRecord {
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(value, "value");
        headers = Map.copyOf(headers);
    }
}
