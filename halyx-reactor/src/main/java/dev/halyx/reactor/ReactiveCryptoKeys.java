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
package dev.halyx.reactor;

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.CryptoKeys;
import dev.halyx.keys.SealedData;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * {@link CryptoKeys} as {@code Mono} and {@code Flux}: sealing for several keys at once, which may be on different
 * providers and clouds, so any one of them can open the data with no rewrap. Calls run on the scheduler of the
 * {@link ReactiveHalyx} that made the first key.
 *
 * <pre>{@code
 * Mono<SealedData> sealed = ReactiveCryptoKeys.encrypt(json, aad, awsKey, azureKey);
 * }</pre>
 */
public final class ReactiveCryptoKeys {

    private ReactiveCryptoKeys() {
    }

    /** Seals {@code plaintext} for every key in {@code keys}: 1 to 16 keys with distinct key ids. */
    public static Mono<SealedData> encrypt(byte[] plaintext, byte[] aad, ReactiveCryptoKey... keys) {
        CryptoKey[] blocking = blocking(keys);
        return keys[0].calls().mono(() -> CryptoKeys.encrypt(plaintext, aad, blocking));
    }

    /** {@code plaintext} sealed for every key in {@code keys} as it arrives; see {@link ReactiveCryptoKey#encryptBody}. */
    public static Flux<ByteBuffer> encryptBody(Publisher<ByteBuffer> plaintext, byte[] aad,
                                               ReactiveCryptoKey... keys) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(aad, "aad");
        CryptoKey[] blocking = blocking(keys);
        return SealedBodies.seal(keys[0].calls(), plaintext, sink -> CryptoKeys.encryptingStream(sink, aad, blocking));
    }

    /** The blocking keys; refuses no keys here, since the first one's scheduler runs the call. */
    private static CryptoKey[] blocking(ReactiveCryptoKey[] keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.length == 0) {
            throw new InvalidArgumentException("Sealed data has 1 to 16 keys (got 0)");
        }
        CryptoKey[] blocking = new CryptoKey[keys.length];
        for (int i = 0; i < keys.length; i++) {
            blocking[i] = Objects.requireNonNull(keys[i], "key").blocking();
        }
        return blocking;
    }
}
