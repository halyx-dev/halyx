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
package dev.halyx.stream;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A record read from a partition. There is nothing to acknowledge: the consumer commits its position in the
 * partition, so every record before a committed offset counts as handled.
 */
public final class ReceivedRecord {

    private final String topic;
    private final int partition;
    private final long offset;
    private final String key;
    private final byte[] value;
    private final Map<String, String> headers;
    private final Instant timestamp;
    private final int deliveryAttempt;

    ReceivedRecord(String topic, int partition, long offset, String key, byte[] value, Map<String, String> headers,
                   Instant timestamp, int deliveryAttempt) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.key = key;
        this.value = value;
        this.headers = Map.copyOf(headers);
        this.timestamp = timestamp;
        this.deliveryAttempt = deliveryAttempt;
    }

    ReceivedRecord withAttempt(int attempt) {
        return new ReceivedRecord(topic, partition, offset, key, value, headers, timestamp, attempt);
    }

    public String topic() {
        return topic;
    }

    public int partition() {
        return partition;
    }

    public long offset() {
        return offset;
    }

    /** The key as UTF-8 text; empty for records sent without one. */
    public Optional<String> key() {
        return Optional.ofNullable(key);
    }

    public byte[] value() {
        return value.clone();
    }

    public String valueAsString() {
        return new String(value, StandardCharsets.UTF_8);
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    public Optional<Instant> timestamp() {
        return Optional.ofNullable(timestamp);
    }

    /**
     * 1 on the first attempt in this consumer, then counting the listener's retries. A record read again after
     * a restart or a rebalance starts at 1, since the broker doesn't count deliveries.
     */
    public int deliveryAttempt() {
        return deliveryAttempt;
    }

    @Override
    public String toString() {
        return "ReceivedRecord[" + topic + "-" + partition + "@" + offset + ", key=" + key + ", size=" + value.length
                + ", attempt=" + deliveryAttempt + "]";
    }
}
