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
package org.kamranzafar.cirrus.cache;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/**
 * Converts cache values to and from bytes. Pick or write one per value type:
 *
 * <pre>{@code
 * Codec<Order> json = Codec.of(mapper::writeValueAsBytes, bytes -> mapper.readValue(bytes, Order.class));
 * }</pre>
 */
public interface Codec<V> {

    byte[] encode(V value);

    V decode(byte[] bytes);

    static Codec<String> string() {
        return StringCodec.INSTANCE;
    }

    static Codec<byte[]> bytes() {
        return BytesCodec.INSTANCE;
    }

    static <V> Codec<V> of(Function<? super V, byte[]> encoder, Function<byte[], ? extends V> decoder) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new Codec<>() {
            @Override
            public byte[] encode(V value) {
                return encoder.apply(value);
            }

            @Override
            public V decode(byte[] bytes) {
                return decoder.apply(bytes);
            }
        };
    }

    enum StringCodec implements Codec<String> {
        INSTANCE;

        @Override
        public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    enum BytesCodec implements Codec<byte[]> {
        INSTANCE;

        @Override
        public byte[] encode(byte[] value) {
            return value;
        }

        @Override
        public byte[] decode(byte[] bytes) {
            return bytes;
        }
    }
}
