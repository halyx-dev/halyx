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

import dev.halyx.capability.Capabilities;
import dev.halyx.storage.AsyncBlobStore;
import dev.halyx.storage.BatchDeleteResult;
import dev.halyx.storage.Blob;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobMetadata;
import dev.halyx.storage.BlobPage;
import dev.halyx.storage.BlobSink;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.BlobSummary;
import dev.halyx.storage.DeleteOptions;
import dev.halyx.storage.GetOptions;
import dev.halyx.storage.ListOptions;
import dev.halyx.storage.MetadataUpdate;
import dev.halyx.storage.PresignRequest;
import dev.halyx.storage.PresignedUpload;
import dev.halyx.storage.PresignedUrl;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.PutResult;
import dev.halyx.storage.StorageTier;
import dev.halyx.storage.UploadedPart;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A {@link BlobStore} as {@code Mono} and {@code Flux}: each call runs when subscribed, once per subscription.
 * {@code put} of content in memory, {@code getBytes}, {@code head}, {@code exists}, {@code delete} and {@code list}
 * use the store's {@link BlobStore#async() asynchronous view}, non-blocking where the provider allows it and on the
 * scheduler where it doesn't; the other calls run on the scheduler of the {@link ReactiveHalyx} that made it. Errors are the blocking API's
 * {@code CloudException}s; a missing blob is an empty {@code Mono} where the blocking API returns an empty
 * {@code Optional}.
 */
public final class ReactiveBlobStore {

    /** Bytes per chunk of {@link #getBody(BlobKey)}. */
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;

    private final BlobStore store;
    private final Calls calls;

    ReactiveBlobStore(BlobStore store, Calls calls) {
        this.store = Objects.requireNonNull(store, "store");
        this.calls = calls;
    }

    public String provider() {
        return store.provider();
    }

    public String bucket() {
        return store.bucket();
    }

    public Capabilities capabilities() {
        return store.capabilities();
    }

    /** The blocking store this wraps. */
    public BlobStore blocking() {
        return store;
    }

    /**
     * The store's asynchronous view for one call, whose blocking work (where the driver has no non-blocking path) runs
     * on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncBlobStore async(Executor blocking) {
        AsyncBlobStore view = store.async(blocking);
        return view != null ? view : AsyncBlobStore.onExecutor(store, blocking); // a mock or proxy without one
    }

    /** Content in memory is sent without blocking where the provider allows it (Amazon S3 does). */
    public Mono<PutResult> put(BlobKey key, BlobContent content) {
        return put(key, content, PutOptions.defaults());
    }

    public Mono<PutResult> put(BlobKey key, BlobContent content, PutOptions options) {
        return content instanceof BlobContent.OfBytes
                ? calls.future(blocking -> async(blocking).put(key, content, options))
                : calls.mono(() -> store.put(key, content, options));
    }

    /** Streams the content through {@code sink}, as {@link BlobStore#write} does; the sink runs on the scheduler. */
    public Mono<PutResult> write(BlobKey key, BlobSink sink, PutOptions options) {
        return calls.mono(() -> store.write(key, sink, options));
    }

    /**
     * Uploads {@code body} as it arrives, through {@link BlobStore#write}: chunks are taken a few at a time,
     * so a slow upload holds back the publisher, and large bodies go up in parts. The blob is stored only if
     * {@code body} completes; if it fails, the upload is aborted and nothing is stored.
     */
    public Mono<PutResult> put(BlobKey key, Publisher<ByteBuffer> body, PutOptions options) {
        return calls.mono(() -> store.write(key, out -> {
            WritableByteChannel channel = Channels.newChannel(out);
            for (ByteBuffer chunk : Flux.from(body).toIterable(4)) {
                while (chunk.hasRemaining()) {
                    channel.write(chunk);
                }
            }
        }, options));
    }

    /** The content in chunks of {@link #DEFAULT_CHUNK_SIZE} bytes; see {@link #getBody(BlobKey, GetOptions, int)}. */
    public Flux<ByteBuffer> getBody(BlobKey key) {
        return getBody(key, GetOptions.defaults(), DEFAULT_CHUNK_SIZE);
    }

    /**
     * The content in chunks of up to {@code chunkSize} bytes, read from the blob as the subscriber asks for
     * them. The blob is closed when the {@code Flux} completes, fails or is cancelled. Fails with
     * {@code NotFoundException} if the blob doesn't exist.
     */
    public Flux<ByteBuffer> getBody(BlobKey key, GetOptions options, int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        return calls.mono(() -> store.get(key, options))
                .doOnDiscard(Blob.class, Blob::close)
                // Eager: the blob is closed before the subscriber sees the completion or error, not after it.
                .flatMapMany(blob -> Flux.using(() -> blob, b -> Flux.<ByteBuffer>generate(sink -> {
                            try {
                                byte[] chunk = b.content().readNBytes(chunkSize);
                                if (chunk.length == 0) {
                                    sink.complete();
                                } else {
                                    sink.next(ByteBuffer.wrap(chunk));
                                }
                            } catch (IOException e) {
                                sink.error(new UncheckedIOException("Could not read " + key, e));
                            }
                        }), Blob::close, true)
                        .subscribeOn(calls.scheduler()));
    }

    /** The whole content; for large blobs, prefer {@link #getBody} or {@link #download}. */
    public Mono<byte[]> getBytes(BlobKey key) {
        return calls.future(blocking -> async(blocking).getBytes(key));
    }

    public Mono<BlobMetadata> download(BlobKey key, Path target) {
        return calls.mono(() -> store.download(key, target));
    }

    public Mono<BlobMetadata> download(BlobKey key, Path target, GetOptions options) {
        return calls.mono(() -> store.download(key, target, options));
    }

    /** The blob's metadata, or empty if it doesn't exist. */
    public Mono<BlobMetadata> head(BlobKey key) {
        return calls.future(blocking -> async(blocking).head(key)).flatMap(Mono::justOrEmpty);
    }

    public Mono<Boolean> exists(BlobKey key) {
        return calls.future(blocking -> async(blocking).exists(key));
    }

    public Mono<Void> delete(BlobKey key) {
        return delete(key, DeleteOptions.defaults());
    }

    public Mono<Void> delete(BlobKey key, DeleteOptions options) {
        return calls.future(blocking -> async(blocking).delete(key, options));
    }

    public Mono<BatchDeleteResult> deleteAll(Collection<BlobKey> keys) {
        return calls.mono(() -> store.deleteAll(keys));
    }

    public Mono<BlobMetadata> updateMetadata(BlobKey key, MetadataUpdate update) {
        return calls.mono(() -> store.updateMetadata(key, update));
    }

    public Mono<Void> setTier(BlobKey key, StorageTier tier) {
        return calls.run(() -> store.setTier(key, tier));
    }

    /** One page of a listing. */
    public Mono<BlobPage> list(ListOptions options) {
        return calls.future(blocking -> async(blocking).list(options));
    }

    /**
     * Every blob under {@code prefix}. Pages are read one at a time as the subscriber asks for items, at most
     * one page ahead of its demand, and none after cancellation.
     */
    public Flux<BlobSummary> listAll(String prefix) {
        return listAll(ListOptions.prefix(prefix));
    }

    /** Every blob {@code options} selects, page by page as {@link #listAll(String)} reads them. */
    public Flux<BlobSummary> listAll(ListOptions options) {
        return calls.pages(() -> store.list(options),
                page -> page.nextPageToken().map(token -> () -> store.list(options.withPageToken(token))),
                BlobPage::items);
    }

    public Mono<PutResult> copy(BlobKey source, BlobKey target) {
        return calls.mono(() -> store.copy(source, target));
    }

    public Mono<PresignedUrl> presign(BlobKey key, PresignRequest request) {
        return calls.mono(() -> store.presign(key, request));
    }

    public Mono<PresignedUrl> presignDownload(BlobKey key, Duration expiry) {
        return calls.mono(() -> store.presignDownload(key, expiry));
    }

    public Mono<PresignedUrl> presignUpload(BlobKey key, Duration expiry) {
        return calls.mono(() -> store.presignUpload(key, expiry));
    }

    public Mono<PresignedUrl> presignUpload(BlobKey key, Duration expiry, String contentType) {
        return calls.mono(() -> store.presignUpload(key, expiry, contentType));
    }

    public Mono<PresignedUpload> startPresignedUpload(BlobKey key, PutOptions options) {
        return calls.mono(() -> store.startPresignedUpload(key, options));
    }

    public Mono<PresignedUrl> presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) {
        return calls.mono(() -> store.presignUploadPart(upload, partNumber, expiry));
    }

    public Mono<PutResult> completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) {
        return calls.mono(() -> store.completePresignedUpload(upload, parts));
    }

    public Mono<Void> abortPresignedUpload(PresignedUpload upload) {
        return calls.run(() -> store.abortPresignedUpload(upload));
    }

    public <T> T unwrap(Class<T> nativeType) {
        return store.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveBlobStore[" + store + "]";
    }
}
