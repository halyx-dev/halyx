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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.PutResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Key stores and keys as {@code Mono} and {@code Flux}, and sealed bodies as they arrive. */
class ReactiveKeyStoreTest {

    private static final byte[] AAD = "orders/o-1001".getBytes(StandardCharsets.UTF_8);
    private static final int SEGMENT = KeyStoreConfig.MIN_SEGMENT_SIZE;

    private Halyx halyx;
    private ReactiveHalyx rx;
    private InMemoryKeys keys;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
        keys = halyx.provider("mem").keyStore().unwrap(InMemoryKeys.class);
        keys.create("orders", KeyPurpose.ENCRYPTION);
        keys.create("backup", KeyPurpose.ENCRYPTION);
        keys.create("tokens", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
    }

    @AfterEach
    void stop() {
        rx.close();
        halyx.close();
    }

    @Test
    void callsRunOnlyWhenSubscribed() {
        ReactiveKeyStore store = rx.provider("mem").keyStore();
        ReactiveCryptoKey key = store.key("orders");
        long wraps = store.statistics().wraps();

        Mono<SealedData> sealed = key.encrypt(bytes(10), AAD);

        assertThat(store.statistics().wraps()).isEqualTo(wraps);
        SealedData once = sealed.block();
        assertThat(store.statistics().wraps()).isEqualTo(wraps + 1);
        assertThat(store.decrypt(once, AAD).block()).isEqualTo(bytes(10));
        assertThat(key.decrypt(once, AAD).block()).isEqualTo(bytes(10));
        StepVerifier.create(sealed.repeat(1)).expectNextCount(2).verifyComplete();
    }

    @Test
    void handlesAreSharedAndNameTheirStore() {
        ReactiveKeyStore store = rx.provider("mem").keyStore();

        assertThat(store.key("orders")).isSameAs(store.key("orders", KeyOptions.defaults()));
        assertThat(store.key("orders").store()).isSameAs(store);
        assertThat(store.key("orders").blocking()).isSameAs(halyx.provider("mem").keyStore().key("orders"));
        assertThat(store.blocking()).isSameAs(halyx.provider("mem").keyStore());
        assertThat(rx.wrap(store.blocking().key("orders")).blocking()).isSameAs(store.key("orders").blocking());
        assertThat(store.keys("").map(m -> m.name()).collectList().block()).contains("orders", "backup", "tokens");
    }

    @Test
    void resultsArriveOnTheGivenSchedulerAndErrorsAreTheBlockingOnes() {
        Scheduler scheduler = ReactiveHalyx.virtualThreadScheduler("app-keys");
        try (ReactiveHalyx own = ReactiveHalyx.of(halyx, scheduler)) {
            ReactiveKeyStore store = own.provider("mem").keyStore();
            String thread = store.key("orders").encrypt(bytes(1), AAD).map(s -> Thread.currentThread().getName())
                    .block();
            assertThat(thread).startsWith("app-keys-");

            SealedData sealed = store.key("orders").encrypt(bytes(1), AAD).block();
            StepVerifier.create(store.decrypt(sealed, new byte[0])).verifyError(DataIntegrityException.class);
            StepVerifier.create(store.key("missing").encrypt(bytes(1), AAD)).verifyError(NotFoundException.class);
            StepVerifier.create(store.key("tokens").encrypt(bytes(1), AAD))
                    .verifyError(InvalidArgumentException.class);
        }
        scheduler.dispose();
    }

    @Test
    void bodiesSealAndOpenAsTheyArrive() {
        ReactiveKeyStore small = rx.provider("mem").keyStore(KeyStoreConfig.builder().segmentSize(SEGMENT).build());
        ReactiveCryptoKey key = small.key("orders");
        for (int length : new int[] {0, 1, SEGMENT, 5 * SEGMENT + 3}) {
            byte[] plaintext = bytes(length);

            byte[] sealed = join(key.encryptBody(chunks(plaintext, 1000), AAD));

            assertThat(key.decrypt(SealedData.parse(sealed), AAD).block()).as("length %d", length)
                    .isEqualTo(plaintext);
            assertThat(join(small.decryptBody(chunks(sealed, 777), AAD))).isEqualTo(plaintext);
            assertThat(join(key.decryptBody(Flux.just(ByteBuffer.wrap(sealed)), AAD))).isEqualTo(plaintext);
        }
    }

    @Test
    void sealedOutputComesOutASegmentAtATime() {
        ReactiveCryptoKey key = rx.provider("mem").keyStore(KeyStoreConfig.builder().segmentSize(SEGMENT).build())
                .key("orders");

        List<ByteBuffer> out = key.encryptBody(chunks(bytes(10 * SEGMENT), SEGMENT / 4), AAD).collectList().block();

        assertThat(out).hasSizeGreaterThan(5); // not one buffer at the end
        assertThat(out.subList(1, out.size() - 1)).allSatisfy(chunk -> assertThat(chunk.remaining())
                .isEqualTo(SEGMENT + 16));
    }

    @Test
    void aFailedOrCancelledBodyDoesNotOpen() {
        ReactiveKeyStore small = rx.provider("mem").keyStore(KeyStoreConfig.builder().segmentSize(SEGMENT).build());
        ReactiveCryptoKey key = small.key("orders");
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        Flux<ByteBuffer> failing = chunks(bytes(3 * SEGMENT), 1000)
                .concatWith(Flux.error(new IllegalStateException("upload broke")));

        StepVerifier.create(key.encryptBody(failing, AAD).doOnNext(chunk -> write(partial, chunk)))
                .thenConsumeWhile(chunk -> true)
                .verifyErrorMessage("upload broke");

        assertThat(partial.size()).isPositive();
        assertThatThrownBy(() -> small.decrypt(SealedData.parse(partial.toByteArray()), AAD).block())
                .isInstanceOf(DataIntegrityException.class);
        StepVerifier.create(small.decryptBody(Flux.just(ByteBuffer.wrap(partial.toByteArray())), AAD))
                .thenConsumeWhile(chunk -> true)
                .verifyError(DataIntegrityException.class);

        ByteArrayOutputStream cancelled = new ByteArrayOutputStream();
        key.encryptBody(chunks(bytes(10 * SEGMENT), 1000), AAD).take(2).doOnNext(chunk -> write(cancelled, chunk))
                .blockLast();
        assertThatThrownBy(() -> small.decrypt(SealedData.parse(cancelled.toByteArray()), AAD).block())
                .isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void aChangedBodyFailsAtTheChangedSegment() {
        ReactiveKeyStore small = rx.provider("mem").keyStore(KeyStoreConfig.builder().segmentSize(SEGMENT).build());
        byte[] sealed = small.key("orders").encrypt(bytes(4 * SEGMENT), AAD).block().toBytes();
        sealed[sealed.length - SEGMENT] ^= 1; // in the last full segment

        // The segments before it open; the subscriber learns of the change before the body completes.
        StepVerifier.create(small.decryptBody(chunks(sealed, 4096), AAD))
                .expectNextCount(ReactiveBlobStore.DEFAULT_CHUNK_SIZE >= 3 * SEGMENT ? 0 : 1)
                .thenConsumeWhile(chunk -> true)
                .verifyError(DataIntegrityException.class);
        StepVerifier.create(small.decryptBody(chunks(sealed, 4096), new byte[0]))
                .verifyError(DataIntegrityException.class);
    }

    @Test
    void severalKeysSealOneBody() {
        ReactiveKeyStore store = rx.provider("mem").keyStore();
        ReactiveKeyStore other = rx.provider("mem").keyStore(KeyStoreConfig.builder().location("dr").build());
        other.unwrap(InMemoryKeys.class).create("orders", KeyPurpose.ENCRYPTION);
        byte[] plaintext = bytes(200_000);

        byte[] body = join(ReactiveCryptoKeys.encryptBody(chunks(plaintext, 8192), AAD, store.key("orders"),
                other.key("orders")));
        SealedData whole = ReactiveCryptoKeys.encrypt(plaintext, AAD, store.key("orders"), other.key("orders"))
                .block();

        assertThat(join(other.decryptBody(chunks(body, 5000), AAD))).isEqualTo(plaintext);
        assertThat(store.decrypt(SealedData.parse(body), AAD).block()).isEqualTo(plaintext);
        assertThat(other.decrypt(whole, AAD).block()).isEqualTo(plaintext);
        assertThatThrownBy(() -> ReactiveCryptoKeys.encrypt(plaintext, AAD))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void rewrapAndAddToTakeReactiveStores() {
        ReactiveKeyStore store = rx.provider("mem").keyStore();
        ReactiveKeyStore other = rx.provider("mem").keyStore(KeyStoreConfig.builder().location("dr").build());
        other.unwrap(InMemoryKeys.class).create("orders", KeyPurpose.ENCRYPTION);
        SealedData sealed = store.key("orders").encrypt(bytes(100), AAD).block();

        SealedData moved = other.key("orders").rewrap(sealed, store).block();
        SealedData both = other.key("orders").addTo(sealed, store).block();
        SealedData within = store.key("backup").rewrap(sealed).block();

        assertThat(other.decrypt(moved, AAD).block()).isEqualTo(bytes(100));
        assertThat(moved.keys()).hasSize(1);
        assertThat(both.keys()).hasSize(2);
        assertThat(store.decrypt(both, AAD).block()).isEqualTo(other.decrypt(both, AAD).block());
        assertThat(store.key("backup").decrypt(within, AAD).block()).isEqualTo(bytes(100));
    }

    @Test
    void signsAndExportsPublicKeys() {
        ReactiveCryptoKey tokens = rx.provider("mem").keyStore().key("tokens");

        Signature signature = tokens.sign(AAD).block();

        assertThat(tokens.verify(AAD, signature).block()).isTrue();
        assertThat(tokens.verify(bytes(3), signature).block()).isFalse();
        assertThat(tokens.publicKeys().block()).isNotEmpty();
        assertThat(tokens.currentPublicKey().block()).isNotNull();
    }

    @Test
    void dataKeysRoundTrip() {
        ReactiveKeyStore store = rx.provider("mem").keyStore();
        ReactiveCryptoKey key = store.key("orders");

        try (DataKey generated = key.generateDataKey().block();
             DataKey unwrapped = store.decryptDataKey(generated.wrapped()).block();
             DataKey again = key.decryptDataKey(key.wrap(generated).block()).block()) {
            assertThat(unwrapped.plaintext()).isEqualTo(generated.plaintext());
            assertThat(again.plaintext()).isEqualTo(generated.plaintext());
        }

    }

    @Test
    void aSealedBlobStreamsToAndFromStorage() {
        // The reactive guide's example, as written
        ReactiveBlobStore invoices = rx.provider("mem").blobStore("acme-invoices");
        BlobKey key = BlobKey.of("exports/orders.csv");
        byte[] plaintext = bytes(300_000);
        Flux<ByteBuffer> csvChunks = chunks(plaintext, 16 * 1024);
        ReactiveCryptoKey data = rx.provider("mem").keyStore().key("orders");

        Mono<PutResult> stored = invoices.put(key, data.encryptBody(csvChunks, AAD), PutOptions.defaults());
        Flux<ByteBuffer> csv = data.decryptBody(invoices.getBody(key), AAD);

        stored.block();
        assertThat(join(csv)).isEqualTo(plaintext);
        assertThat(invoices.getBytes(key).block()).isNotEqualTo(plaintext);
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        new Random(length).nextBytes(bytes);
        return bytes;
    }

    private static Flux<ByteBuffer> chunks(byte[] data, int size) {
        return Flux.range(0, (data.length + size - 1) / size)
                .map(i -> ByteBuffer.wrap(data, i * size, Math.min(size, data.length - i * size)).slice());
    }

    private static byte[] join(Flux<ByteBuffer> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.doOnNext(chunk -> write(out, chunk)).blockLast(Duration.ofMinutes(1));
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, ByteBuffer chunk) {
        byte[] bytes = new byte[chunk.remaining()];
        chunk.duplicate().get(bytes);
        out.writeBytes(bytes);
    }
}
