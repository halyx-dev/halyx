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

import dev.halyx.cache.AsyncCache;
import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheLock;
import dev.halyx.cache.CacheStatistics;
import dev.halyx.capability.Capabilities;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.EventPublisher;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.keys.AsyncCryptoKey;
import dev.halyx.keys.AsyncKeyStore;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreStatistics;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.SealedHeader;
import dev.halyx.keys.Signature;
import dev.halyx.keys.WrappedKey;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.MessageConsumer;
import dev.halyx.messaging.MessageListener;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.PublishResult;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.IndexQuery;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Transaction;
import dev.halyx.nosql.Version;
import dev.halyx.nosql.WriteOptions;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterChanges;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreStatistics;
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
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.RecordListener;
import dev.halyx.stream.SendResult;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamListener;
import dev.halyx.stream.StreamProducer;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The reactive services seen through their blocking interfaces, so the conformance kits run through every
 * adapter method: each call subscribes and waits. Not part of the API. The exceptions are a blob's open stream
 * ({@code get}, which the reactive API doesn't hand out) and listeners, which the kits read statistics from and
 * which run on the blocking consumer; the streaming adapters have tests of their own. A key's
 * {@code encryptingStream} is the blocking one too, since an {@code OutputStream} can't wait for the {@code Flux}
 * that seals behind it; {@code encryptBody} has tests of its own.
 */
final class BlockingViews {

    private BlockingViews() {
    }

    @SuppressWarnings("unchecked")
    private static <V> CompletableFuture<V> narrow(CompletableFuture<? extends V> future) {
        return (CompletableFuture<V>) future;
    }

    private static <T> T await(Mono<T> mono) {
        return mono.block(Duration.ofMinutes(2));
    }

    private static <T> Optional<T> awaitOptional(Mono<T> mono) {
        return mono.blockOptional(Duration.ofMinutes(2));
    }

    static BlobStore of(ReactiveBlobStore rx) {
        return new BlobStore() {
            public String provider() { return rx.provider(); }
            public String bucket() { return rx.bucket(); }
            public PutResult put(BlobKey key, BlobContent content, PutOptions options) { return await(rx.put(key, content, options)); }
            public PutResult write(BlobKey key, BlobSink sink, PutOptions options) { return await(rx.write(key, sink, options)); }
            public Blob get(BlobKey key, GetOptions options) { return rx.blocking().get(key, options); }
            public byte[] getBytes(BlobKey key) { return await(rx.getBytes(key)); }
            public BlobMetadata download(BlobKey key, Path target, GetOptions options) { return await(rx.download(key, target, options)); }
            public Optional<BlobMetadata> head(BlobKey key) { return awaitOptional(rx.head(key)); }
            public boolean exists(BlobKey key) { return await(rx.exists(key)); }
            public void delete(BlobKey key, DeleteOptions options) { await(rx.delete(key, options)); }
            public BatchDeleteResult deleteAll(Collection<BlobKey> keys) { return await(rx.deleteAll(keys)); }
            public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) { return await(rx.updateMetadata(key, update)); }
            public void setTier(BlobKey key, StorageTier tier) { await(rx.setTier(key, tier)); }
            public BlobPage list(ListOptions options) { return await(rx.list(options)); }
            public Stream<BlobSummary> listAll(String prefix) { return rx.listAll(prefix).toStream(); }
            public PutResult copy(BlobKey source, BlobKey target) { return await(rx.copy(source, target)); }
            public PresignedUrl presign(BlobKey key, PresignRequest request) { return await(rx.presign(key, request)); }
            public PresignedUrl presignDownload(BlobKey key, Duration expiry) { return await(rx.presignDownload(key, expiry)); }
            public PresignedUrl presignUpload(BlobKey key, Duration expiry) { return await(rx.presignUpload(key, expiry)); }
            public PresignedUrl presignUpload(BlobKey key, Duration expiry, String contentType) { return await(rx.presignUpload(key, expiry, contentType)); }
            public PresignedUpload startPresignedUpload(BlobKey key, PutOptions options) { return await(rx.startPresignedUpload(key, options)); }
            public PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) { return await(rx.presignUploadPart(upload, partNumber, expiry)); }
            public PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) { return await(rx.completePresignedUpload(upload, parts)); }
            public void abortPresignedUpload(PresignedUpload upload) { await(rx.abortPresignedUpload(upload)); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static DocumentStore of(ReactiveDocumentStore rx) {
        return new DocumentStore() {
            public String provider() { return rx.provider(); }
            public TableConfig table() { return rx.table(); }
            public Optional<Item> get(Key key) { return awaitOptional(rx.get(key)); }
            public Item put(Key key, Document document, WriteOptions options) { return await(rx.put(key, document, options)); }
            public Item update(Key key, DocumentUpdate update, WriteOptions options) { return await(rx.update(key, update, options)); }
            public boolean delete(Key key, Version ifVersion) { return await(rx.delete(key, ifVersion)); }
            public Map<Key, Item> getAll(Collection<Key> keys) { return await(rx.getAll(keys)); }
            public void putAll(Map<Key, Document> items) { await(rx.putAll(items)); }
            public QueryPage query(Query query) { return await(rx.query(query)); }
            public Stream<Item> queryAll(Query query) { return rx.queryAll(query).toStream(); }
            public Map<Key, Version> transact(Transaction transaction) { return await(rx.transact(transaction)); }
            public QueryPage queryIndex(IndexQuery query) { return await(rx.queryIndex(query)); }
            public Stream<Item> queryIndexAll(IndexQuery query) { return rx.queryIndexAll(query).toStream(); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static Publisher of(ReactivePublisher rx) {
        return new Publisher() {
            public String provider() { return rx.provider(); }
            public String topic() { return rx.topic(); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public PublishResult publish(OutgoingMessage message) { return await(rx.publish(message)); }
            public List<PublishResult> publishAll(List<OutgoingMessage> messages) { return await(rx.publishAll(messages)); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static Subscription of(ReactiveSubscription rx) {
        return new Subscription() {
            public String provider() { return rx.provider(); }
            public String name() { return rx.name(); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public List<ReceivedMessage> receive(int max, Duration wait) { return await(rx.receive(max, wait)); }
            public MessageConsumer listen(MessageListener listener, ConsumerOptions options) { return rx.blocking().listen(listener, options); }
            public Duration defaultLease() { return rx.defaultLease(); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static EventPublisher of(ReactiveEventPublisher rx) {
        return new EventPublisher() {
            public String provider() { return rx.provider(); }
            public String bus() { return rx.bus(); }
            public void publish(CloudEvent event) { await(rx.publish(event)); }
            public void publishAll(List<CloudEvent> events) { await(rx.publishAll(events)); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static FunctionInvoker of(ReactiveFunctionInvoker rx) {
        return new FunctionInvoker() {
            public String provider() { return rx.provider(); }
            public String function() { return rx.function(); }
            public FunctionResponse invoke(FunctionRequest request) { return await(rx.invoke(request)); }
            public void trigger(FunctionRequest request) { await(rx.trigger(request)); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static StreamProducer of(ReactiveStreamProducer rx) {
        return new StreamProducer() {
            public String provider() { return rx.provider(); }
            public String topic() { return rx.topic(); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public SendResult send(OutgoingRecord record) { return await(rx.send(record)); }
            public List<SendResult> sendAll(List<OutgoingRecord> records) { return await(rx.sendAll(records)); }
            public int partitionCount() { return await(rx.partitionCount()); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static StreamConsumer of(ReactiveStreamConsumer rx) {
        return new StreamConsumer() {
            public String provider() { return rx.provider(); }
            public String topic() { return rx.topic(); }
            public String group() { return rx.group(); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public List<ReceivedRecord> poll(Duration wait) { return await(rx.poll(wait)); }
            public void commit() { await(rx.commit()); }
            public StreamListener listen(RecordListener listener, ListenerOptions options) { return rx.blocking().listen(listener, options); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
            public void close() { await(rx.close()); }
        };
    }

    static <V> Cache<V> of(ReactiveCache<V> rx) {
        return new Cache<V>() {
            public String provider() { return rx.provider(); }
            public String namespace() { return rx.namespace(); }
            public Optional<V> get(String key) { return awaitOptional(rx.get(key)); }
            public Map<String, V> getAll(Collection<String> keys) { return await(rx.getAll(keys)); }
            public void put(String key, V value) { await(rx.put(key, value)); }
            public void put(String key, V value, Duration ttl) { await(rx.put(key, value, ttl)); }
            public boolean putIfAbsent(String key, V value) { return await(rx.putIfAbsent(key, value)); }
            public boolean putIfAbsent(String key, V value, Duration ttl) { return await(rx.putIfAbsent(key, value, ttl)); }
            public void putAll(Map<String, ? extends V> entries) { await(rx.putAll(entries)); }
            public void putAll(Map<String, ? extends V> entries, Duration ttl) { await(rx.putAll(entries, ttl)); }
            public V getOrLoad(String key, Supplier<? extends V> loader) { return await(rx.getOrLoad(key, Mono.fromSupplier(loader))); }
            public V getOrLoad(String key, Duration ttl, Supplier<? extends V> loader) { return await(rx.getOrLoad(key, ttl, Mono.fromSupplier(loader))); }
            // The async view subscribes at once, so calls start in the order they are made, as the cache's own do.
            public AsyncCache<V> async(Executor blocking) {
                Cache<V> self = this;
                return new AsyncCache<V>() {
                    public String provider() { return rx.provider(); }
                    public String namespace() { return rx.namespace(); }
                    public CompletableFuture<Optional<V>> get(String key) { return rx.get(key).map(Optional::of).defaultIfEmpty(Optional.empty()).toFuture(); }
                    public CompletableFuture<Map<String, V>> getAll(Collection<String> keys) { return rx.getAll(keys).toFuture(); }
                    public CompletableFuture<Void> put(String key, V value) { return rx.put(key, value).toFuture(); }
                    public CompletableFuture<Void> put(String key, V value, Duration ttl) { return rx.put(key, value, ttl).toFuture(); }
                    public CompletableFuture<Boolean> delete(String key) { return rx.delete(key).toFuture(); }
                    public CompletableFuture<Boolean> exists(String key) { return rx.exists(key).toFuture(); }
                    public CompletableFuture<V> getOrLoad(String key, Supplier<? extends CompletableFuture<? extends V>> loader) { return rx.getOrLoad(key, Mono.fromFuture(() -> narrow(loader.get()))).toFuture(); }
                    public CompletableFuture<V> getOrLoad(String key, Duration ttl, Supplier<? extends CompletableFuture<? extends V>> loader) { return rx.getOrLoad(key, ttl, Mono.fromFuture(() -> narrow(loader.get()))).toFuture(); }
                    public Cache<V> blocking() { return self; }
                };
            }
            public boolean delete(String key) { return await(rx.delete(key)); }
            public long deleteAll(Collection<String> keys) { return await(rx.deleteAll(keys)); }
            public boolean exists(String key) { return await(rx.exists(key)); }
            public Optional<Duration> ttl(String key) { return awaitOptional(rx.ttl(key)); }
            public boolean expire(String key, Duration ttl) { return await(rx.expire(key, ttl)); }
            public long increment(String key, long delta) { return await(rx.increment(key, delta)); }
            public long increment(String key, long delta, Duration ttl) { return await(rx.increment(key, delta, ttl)); }
            public boolean update(String key, V value) { return await(rx.update(key, value)); }
            public boolean replace(String key, V expected, V value) { return await(rx.replace(key, expected, value)); }
            public Optional<V> getAndDelete(String key) { return awaitOptional(rx.getAndDelete(key)); }
            public boolean deleteIfEquals(String key, V expected) { return await(rx.deleteIfEquals(key, expected)); }
            public long clear() { return await(rx.clear()); }
            public Optional<CacheLock> tryLock(String name, Duration lease) { return awaitOptional(rx.tryLock(name, lease)); }
            public Optional<CacheLock> lock(String name, Duration lease, Duration wait) { return awaitOptional(rx.lock(name, lease, wait)); }
            public void ping() { await(rx.ping()); }
            public CacheStatistics statistics() { return rx.statistics(); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    static ParameterStore of(ReactiveParameterStore rx) {
        return new ParameterStore() {
            public String provider() { return rx.provider(); }
            public ParameterKind kind() { return rx.kind(); }
            public Optional<Parameter> get(String name) { return awaitOptional(rx.get(name)); }
            public Optional<Parameter> get(String name, String version) { return awaitOptional(rx.get(name, version)); }
            public String require(String name) { return await(rx.require(name)); }
            public String require(String name, String field) { return await(rx.require(name, field)); }
            public Map<String, Parameter> getAll(String prefix) { return await(rx.getAll(prefix)); }
            public Parameter put(String name, String value) { return await(rx.put(name, value)); }
            public void delete(String name) { await(rx.delete(name)); }
            public void refresh() { await(rx.refresh()); }
            public void ping() { await(rx.ping()); }
            public AutoCloseable watch(String prefix, Duration interval, Consumer<ParameterChanges> listener) {
                Disposable watching = rx.watch(prefix, interval).subscribe(listener);
                return watching::dispose;
            }
            public ParameterStoreStatistics statistics() { return rx.statistics(); }
            public Capabilities capabilities() { return rx.capabilities(); }
            public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
        };
    }

    /**
     * Key store and key views, one per blocking store and key, so the kit's identity checks ({@code key.store()},
     * equal options sharing a key) hold through them. Stores the kit makes itself, outside the views, are wrapped
     * with {@code wrap} where a reactive call takes one.
     */
    static final class KeyViews {

        private final Function<KeyStore, ReactiveKeyStore> wrap;
        private final Map<KeyStore, ReactiveKeyStore> reactive = new ConcurrentHashMap<>();
        private final Map<KeyStore, KeyStore> stores = new ConcurrentHashMap<>();
        private final Map<CryptoKey, CryptoKey> keys = new ConcurrentHashMap<>();

        KeyViews(Function<KeyStore, ReactiveKeyStore> wrap) {
            this.wrap = wrap;
        }

        /** The reactive store behind a view, or a new reactive view of a store the kit made itself. */
        private ReactiveKeyStore reactive(KeyStore store) {
            ReactiveKeyStore known = reactive.get(store);
            return known != null ? known : wrap.apply(store);
        }

        KeyStore of(ReactiveKeyStore rx) {
            return stores.computeIfAbsent(rx.blocking(), blocking -> {
                KeyStore view = new KeyStore() {
                    public String provider() { return rx.provider(); }
                    public Optional<String> location() { return rx.location(); }
                    public CryptoKey key(String name) { return of(rx.key(name)); }
                    public CryptoKey key(String name, KeyOptions options) { return of(rx.key(name, options)); }
                    public List<KeyMetadata> keys(String prefix) { return await(rx.keys(prefix).collectList()); }
                    public byte[] decrypt(SealedData sealed, byte[] aad) { return await(rx.decrypt(sealed, aad)); }
                    public DataKey decryptDataKey(WrappedKey wrapped) { return await(rx.decryptDataKey(wrapped)); }
                    public InputStream decryptingStream(InputStream sealed, byte[] aad) { return inputStream(rx.decryptBody(body(sealed), aad)); }
                    // No reactive form: the ranged read is a building block for blob views, which use the blocking store.
                    public InputStream decryptingStream(SealedHeader header, InputStream segments, long sealedLength,
                                                        long plaintextOffset, long plaintextLength, byte[] aad) {
                        return rx.blocking().decryptingStream(header, segments, sealedLength, plaintextOffset,
                                plaintextLength, aad);
                    }
                    public void ping() { await(rx.ping()); }
                    public KeyStoreStatistics statistics() { return rx.statistics(); }
                    public Capabilities capabilities() { return rx.capabilities(); }
                    public AsyncKeyStore async(Executor blocking) {
                        KeyStore self = this;
                        return new AsyncKeyStore() {
                            public String provider() { return rx.provider(); }
                            public CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad) { return rx.decrypt(sealed, aad).toFuture(); }
                            public CompletableFuture<DataKey> decryptDataKey(WrappedKey wrapped) { return rx.decryptDataKey(wrapped).toFuture(); }
                            public CompletableFuture<List<KeyMetadata>> keys(String prefix) { return rx.keys(prefix).collectList().toFuture(); }
                            public CompletableFuture<Void> ping() { return rx.ping().toFuture(); }
                            public KeyStore blocking() { return self; }
                        };
                    }
                    public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
                };
                reactive.put(view, rx);
                return view;
            });
        }

        CryptoKey of(ReactiveCryptoKey rx) {
            return keys.computeIfAbsent(rx.blocking(), blocking -> new CryptoKey() {
                public KeyStore store() { return of(rx.store()); }
                public String name() { return rx.name(); }
                public String provider() { return rx.provider(); }
                public KeyOptions options() { return blocking.options(); }
                public KeyMetadata metadata() { return await(rx.metadata()); }
                public SealedData encrypt(byte[] plaintext, byte[] aad) { return await(rx.encrypt(plaintext, aad)); }
                public byte[] decrypt(SealedData sealed, byte[] aad) { return await(rx.decrypt(sealed, aad)); }
                public OutputStream encryptingStream(OutputStream sink, byte[] aad) { return rx.blocking().encryptingStream(sink, aad); }
                public DataKey generateDataKey() { return await(rx.generateDataKey()); }
                public DataKey decryptDataKey(WrappedKey wrapped) { return await(rx.decryptDataKey(wrapped)); }
                public WrappedKey wrap(DataKey dataKey) { return await(rx.wrap(dataKey)); }
                public SealedData rewrap(SealedData sealed) { return await(rx.rewrap(sealed)); }
                public SealedData rewrap(SealedData sealed, KeyStore from) { return await(rx.rewrap(sealed, reactive(from))); }
                public SealedData addTo(SealedData sealed, KeyStore from) { return await(rx.addTo(sealed, reactive(from))); }
                public Signature sign(byte[] data) { return await(rx.sign(data)); }
                public Signature signDigest(byte[] digest) { return await(rx.signDigest(digest)); }
                public boolean verify(byte[] data, Signature signature) { return await(rx.verify(data, signature)); }
                public byte[] mac(byte[] data) { return await(rx.mac(data)); }
                public boolean verifyMac(byte[] data, byte[] mac) { return await(rx.verifyMac(data, mac)); }
                public PublicKey publicKey(String version) { return await(rx.publicKey(version)); }
                public PublicKey currentPublicKey() { return await(rx.currentPublicKey()); }
                public Map<String, PublicKey> publicKeys() { return await(rx.publicKeys()); }
                public Capabilities capabilities() { return rx.capabilities(); }
                public AsyncCryptoKey async(Executor executor) {
                    CryptoKey self = this;
                    return new AsyncCryptoKey() {
                        public String provider() { return rx.provider(); }
                        public String name() { return rx.name(); }
                        public CompletableFuture<KeyMetadata> metadata() { return rx.metadata().toFuture(); }
                        public CompletableFuture<SealedData> encrypt(byte[] plaintext, byte[] aad) { return rx.encrypt(plaintext, aad).toFuture(); }
                        public CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad) { return rx.decrypt(sealed, aad).toFuture(); }
                        public CompletableFuture<DataKey> generateDataKey() { return rx.generateDataKey().toFuture(); }
                        public CompletableFuture<Signature> sign(byte[] data) { return rx.sign(data).toFuture(); }
                        public CompletableFuture<Signature> signDigest(byte[] digest) { return rx.signDigest(digest).toFuture(); }
                        public CompletableFuture<Boolean> verify(byte[] data, Signature signature) { return rx.verify(data, signature).toFuture(); }
                        public CompletableFuture<byte[]> mac(byte[] data) { return rx.mac(data).toFuture(); }
                        public CompletableFuture<Boolean> verifyMac(byte[] data, byte[] mac) { return rx.verifyMac(data, mac).toFuture(); }
                        public CryptoKey blocking() { return self; }
                    };
                }
                public <T> T unwrap(Class<T> nativeType) { return rx.unwrap(nativeType); }
            });
        }

        /** {@code in} as a body of 1000-byte chunks, so segments span chunks; closed however the body ends. */
        private static Flux<ByteBuffer> body(InputStream in) {
            return Flux.<ByteBuffer>generate(sink -> {
                try {
                    byte[] chunk = in.readNBytes(1000);
                    if (chunk.length == 0) {
                        sink.complete();
                    } else {
                        sink.next(ByteBuffer.wrap(chunk));
                    }
                } catch (IOException e) {
                    sink.error(new UncheckedIOException(e));
                }
            }).doFinally(signal -> {
                try {
                    in.close();
                } catch (IOException e) {
                    // already done with it
                }
            });
        }

        /** A body read as a stream; a failed body throws its error from {@code read}. */
        private static InputStream inputStream(Flux<ByteBuffer> body) {
            Stream<ByteBuffer> chunks = body.toStream(4);
            Iterator<ByteBuffer> iterator = chunks.iterator();
            return new InputStream() {
                private ByteBuffer current;

                @Override
                public int read() {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (len == 0) {
                        return 0;
                    }
                    while (current == null || !current.hasRemaining()) {
                        if (!iterator.hasNext()) {
                            return -1;
                        }
                        current = iterator.next();
                    }
                    int n = Math.min(len, current.remaining());
                    current.get(b, off, n);
                    return n;
                }

                @Override
                public void close() {
                    chunks.close();
                }
            };
        }
    }
}
