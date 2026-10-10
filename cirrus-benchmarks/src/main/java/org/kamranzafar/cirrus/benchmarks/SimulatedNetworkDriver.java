package org.kamranzafar.cirrus.benchmarks;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobHeaders;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobMetadata;
import org.kamranzafar.cirrus.storage.BlobPage;
import org.kamranzafar.cirrus.storage.BlobSummary;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.DeleteOptions;
import org.kamranzafar.cirrus.storage.ETag;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.kamranzafar.cirrus.storage.MetadataUpdate;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.StorageCapability;
import org.kamranzafar.cirrus.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.cirrus.storage.spi.MultipartUploadSession;

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
