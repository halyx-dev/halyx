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
package dev.halyx.benchmarks;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.storage.Blob;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobHeaders;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobMetadata;
import dev.halyx.storage.BlobPage;
import dev.halyx.storage.BlobSummary;
import dev.halyx.storage.Checksums;
import dev.halyx.storage.DeleteOptions;
import dev.halyx.storage.ETag;
import dev.halyx.storage.GetOptions;
import dev.halyx.storage.ListOptions;
import dev.halyx.storage.MetadataUpdate;
import dev.halyx.storage.PutOptions;
import dev.halyx.storage.PutResult;
import dev.halyx.storage.StorageCapability;
import dev.halyx.storage.spi.AbstractBlobStoreDriver;
import dev.halyx.storage.spi.MultipartUploadSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.LockSupport;

/**
 * An in-memory blob store behind a simulated network: every request pays a round-trip {@code latency},
 * and each connection moves at most {@code bytesPerSecond}, like a single TCP stream to a cloud store.
 * Concurrency helps exactly as it does against real storage: by overlapping round trips and using several
 * connections at once. Listing pages hold {@code pageSize} entries.
 */
final class SimulatedNetworkDriver extends AbstractBlobStoreDriver {

    private record Entry(byte[] data, ETag etag) {
    }

    private final long latencyNanos;
    private final long bytesPerSecond;
    private final Map<String, Entry> blobs = new ConcurrentHashMap<>();

    SimulatedNetworkDriver(long latencyMillis, long bytesPerSecond) {
        super("simulated", "bench", Capabilities.of(StorageCapability.RANGE_READ, StorageCapability.CHECKSUM));
        this.latencyNanos = latencyMillis * 1_000_000;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Stores a blob without paying for the network, to set up a benchmark. */
    void seed(BlobKey key, byte[] data) {
        blobs.put(key.value(), new Entry(data, ETag.of(UUID.randomUUID().toString())));
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
        roundTrip();
        try (InputStream in = content.openStream()) {
            byte[] data = in.readAllBytes();
            transfer(data.length);
            return store(key, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        roundTrip();
        var parts = new ConcurrentSkipListMap<Integer, byte[]>();
        return new MultipartUploadSession() {
            @Override
            public void uploadPart(int partNumber, byte[] data, int length) {
                roundTrip();
                transfer(length);
                parts.put(partNumber, Arrays.copyOf(data, length));
            }

            @Override
            public PutResult complete(Checksums expected) {
                roundTrip();
                var out = new ByteArrayOutputStream();
                parts.values().forEach(out::writeBytes);
                return store(key, out.toByteArray());
            }

            @Override
            public void abort() {
                parts.clear();
            }
        };
    }

    private PutResult store(BlobKey key, byte[] data) {
        Entry entry = new Entry(data, ETag.of(UUID.randomUUID().toString()));
        blobs.put(key.value(), entry);
        return new PutResult(key, entry.etag());
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        roundTrip();
        Entry entry = entry(key);
        if (options.ifMatch().isPresent() && !options.ifMatch().get().equals(entry.etag())) {
            throw new PreconditionFailedException("changed: " + key);
        }
        int from = (int) options.range().map(r -> r.offset()).orElse(0L).longValue();
        int to = options.range().flatMap(r -> r.length().isPresent()
                        ? Optional.of((int) Math.min(entry.data().length, r.offset() + r.length().getAsLong()))
                        : Optional.empty())
                .orElse(entry.data().length);
        return new Blob(metadata(key, entry), new ThrottledStream(Arrays.copyOfRange(entry.data(), from, to)));
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        roundTrip();
        Entry entry = blobs.get(key.value());
        return Optional.ofNullable(entry).map(e -> metadata(key, e));
    }

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        roundTrip();
        blobs.remove(key.value());
    }

    @Override
    public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BlobPage list(ListOptions options) {
        roundTrip();
        List<String> keys = blobs.keySet().stream().filter(k -> k.startsWith(options.prefix().orElse(""))).sorted().toList();
        int start = options.pageToken().map(Integer::parseInt).orElse(0);
        int end = Math.min(keys.size(), start + options.pageSize());
        List<BlobSummary> items = keys.subList(start, end).stream()
                .map(k -> new BlobSummary(BlobKey.of(k), blobs.get(k).data().length, blobs.get(k).etag(), Instant.EPOCH))
                .toList();
        return new BlobPage(items, end < keys.size() ? Optional.of(String.valueOf(end)) : Optional.empty());
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of();
    }

    private Entry entry(BlobKey key) {
        Entry entry = blobs.get(key.value());
        if (entry == null) {
            throw new NotFoundException("No blob " + key);
        }
        return entry;
    }

    private static BlobMetadata metadata(BlobKey key, Entry entry) {
        return new BlobMetadata(key, entry.data().length, entry.etag(), Instant.EPOCH, BlobHeaders.none(),
                Map.of(), Optional.empty(), Checksums.none());
    }

    private void roundTrip() {
        LockSupport.parkNanos(latencyNanos);
    }

    private void transfer(long bytes) {
        LockSupport.parkNanos(bytes * 1_000_000_000L / bytesPerSecond);
    }

    /** A response body that arrives no faster than one connection's bandwidth. */
    private final class ThrottledStream extends InputStream {
        private static final int CHUNK = 64 * 1024;
        private final byte[] data;
        private int position;

        ThrottledStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, CHUNK), data.length - position);
            transfer(n);
            System.arraycopy(data, position, b, off, n);
            position += n;
            return n;
        }
    }
}
