package org.kamranzafar.stratum.memory;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.InvalidArgumentException;
import org.kamranzafar.stratum.error.NotFoundException;
import org.kamranzafar.stratum.error.PreconditionFailedException;
import org.kamranzafar.stratum.storage.Blob;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobHeaders;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobMetadata;
import org.kamranzafar.stratum.storage.BlobPage;
import org.kamranzafar.stratum.storage.BlobSummary;
import org.kamranzafar.stratum.storage.Checksums;
import org.kamranzafar.stratum.storage.DeleteOptions;
import org.kamranzafar.stratum.storage.MetadataUpdate;
import org.kamranzafar.stratum.storage.StorageTier;
import org.kamranzafar.stratum.storage.ByteRange;
import org.kamranzafar.stratum.storage.ETag;
import org.kamranzafar.stratum.storage.GetOptions;
import org.kamranzafar.stratum.storage.ListOptions;
import org.kamranzafar.stratum.storage.PutOptions;
import org.kamranzafar.stratum.storage.PutResult;
import org.kamranzafar.stratum.storage.StorageCapability;
import org.kamranzafar.stratum.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.stratum.storage.spi.ChecksumCalculator;
import org.kamranzafar.stratum.storage.spi.MultipartUploadSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;

final class InMemoryBlobStoreDriver extends AbstractBlobStoreDriver {

    record Entry(byte[] data, BlobHeaders headers, Map<String, String> metadata, ETag etag, Instant lastModified,
                 StorageTier tier, Checksums checksums) {

        Entry with(BlobHeaders newHeaders, Map<String, String> newMetadata, StorageTier newTier) {
            return new Entry(data, newHeaders, newMetadata, newETag(), Instant.now(), newTier, checksums);
        }
    }

    private final ConcurrentNavigableMap<String, Entry> blobs;

    InMemoryBlobStoreDriver(String bucket, ConcurrentNavigableMap<String, Entry> blobs) {
        super(InMemoryProvider.ID, bucket, Capabilities.of(
                StorageCapability.CONDITIONAL_WRITE,
                StorageCapability.RANGE_READ,
                StorageCapability.SERVER_SIDE_COPY,
                StorageCapability.USER_METADATA,
                StorageCapability.CHECKSUM,
                StorageCapability.CONDITIONAL_DELETE,
                StorageCapability.STORAGE_TIERS));
        this.blobs = blobs;
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
        return store(key, read(content), options);
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        var parts = new ByteArrayOutputStream();
        return new MultipartUploadSession() {
            @Override
            public void uploadPart(int partNumber, byte[] data, int length) {
                parts.write(data, 0, length);
            }

            @Override
            public PutResult complete(Checksums expected) {
                return store(key, parts.toByteArray(), options);
            }

            @Override
            public void abort() {
                parts.reset();
            }
        };
    }

    private PutResult store(BlobKey key, byte[] data, PutOptions options) {
        Entry stored = blobs.compute(key.value(), (k, existing) -> {
            if (options.ifNotExists() && existing != null) {
                throw new PreconditionFailedException("Blob already exists: " + key);
            }
            options.ifMatch().ifPresent(expected -> {
                if (existing == null || !existing.etag().equals(expected)) {
                    throw new PreconditionFailedException("ETag mismatch for " + key);
                }
            });
            return new Entry(data, options.headers(), options.metadata(), newETag(), Instant.now(),
                    options.tier().orElse(StorageTier.HOT), ChecksumCalculator.of(data, 0, data.length));
        });
        return new PutResult(key, stored.etag(), stored.checksums());
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        Entry entry = require(key);
        options.ifMatch().ifPresent(expected -> {
            if (!entry.etag().equals(expected)) {
                throw new PreconditionFailedException("ETag mismatch for " + key);
            }
        });
        byte[] data = options.range().map(r -> slice(entry.data(), r)).orElse(entry.data());
        return new Blob(metadata(key, entry), new ByteArrayInputStream(data));
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        return Optional.ofNullable(blobs.get(key.value())).map(entry -> metadata(key, entry));
    }

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        if (options.ifMatchETag().isEmpty()) {
            blobs.remove(key.value());
            return;
        }
        ETag expected = options.ifMatchETag().get();
        blobs.compute(key.value(), (k, existing) -> {
            if (existing == null || !existing.etag().equals(expected)) {
                throw new PreconditionFailedException("ETag mismatch for " + key);
            }
            return null;
        });
    }

    @Override
    public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
        Entry updated = blobs.compute(key.value(), (k, existing) -> {
            if (existing == null) {
                throw update.ifMatch().isPresent()
                        ? new PreconditionFailedException("No blob " + key)
                        : new NotFoundException("No blob " + bucket + "/" + key, null, provider, null);
            }
            if (update.ifMatch().isPresent() && !existing.etag().equals(update.ifMatch().get())) {
                throw new PreconditionFailedException("ETag mismatch for " + key);
            }
            return existing.with(update.headers(), update.metadata(), existing.tier());
        });
        return metadata(key, updated);
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        blobs.compute(key.value(), (k, existing) -> {
            if (existing == null) {
                throw new NotFoundException("No blob " + bucket + "/" + key, null, provider, null);
            }
            return new Entry(existing.data(), existing.headers(), existing.metadata(), existing.etag(),
                    existing.lastModified(), tier, existing.checksums());
        });
    }

    @Override
    public BlobPage list(ListOptions options) {
        String prefix = options.prefix().orElse("");
        String delimiter = options.delimiter().orElse(null);
        String token = options.pageToken().orElse(null);
        var candidates = token != null ? blobs.tailMap(token, false) : blobs.tailMap(prefix, true);
        List<BlobSummary> items = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        String last = null;
        for (var e : candidates.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix)) {
                break;
            }
            // When resuming after a common prefix, skip the keys rolled up into it.
            if (token != null && delimiter != null && token.endsWith(delimiter) && key.startsWith(token)) {
                continue;
            }
            String commonPrefix = commonPrefix(key, prefix, delimiter);
            if (commonPrefix != null && commonPrefix.equals(last)) {
                continue;
            }
            if (items.size() + prefixes.size() == options.pageSize()) {
                return new BlobPage(items, prefixes, Optional.of(last));
            }
            if (commonPrefix != null) {
                prefixes.add(commonPrefix);
                last = commonPrefix;
            } else {
                Entry entry = e.getValue();
                items.add(new BlobSummary(BlobKey.of(key), entry.data().length, entry.etag(), entry.lastModified(),
                        Optional.of(entry.tier())));
                last = key;
            }
        }
        return new BlobPage(items, prefixes, Optional.empty());
    }

    /** The common prefix {@code key} rolls up into, or null if it is directly under {@code prefix}. */
    private static String commonPrefix(String key, String prefix, String delimiter) {
        if (delimiter == null) {
            return null;
        }
        int index = key.indexOf(delimiter, prefix.length());
        return index < 0 ? null : key.substring(0, index + delimiter.length());
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        Entry entry = require(source);
        Entry copy = new Entry(entry.data(), entry.headers(), entry.metadata(), newETag(), Instant.now(), entry.tier(),
                entry.checksums());
        blobs.put(target.value(), copy);
        return new PutResult(target, copy.etag());
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of();
    }

    private Entry require(BlobKey key) {
        Entry entry = blobs.get(key.value());
        if (entry == null) {
            throw new NotFoundException("No blob " + bucket + "/" + key, null, provider, null);
        }
        return entry;
    }

    private BlobMetadata metadata(BlobKey key, Entry entry) {
        return new BlobMetadata(key, entry.data().length, entry.etag(), entry.lastModified(), entry.headers(),
                entry.metadata(), Optional.of(entry.tier()), entry.checksums());
    }

    private static byte[] slice(byte[] data, ByteRange range) {
        if (range.offset() >= data.length) {
            throw new InvalidArgumentException("Range offset " + range.offset() + " beyond blob size " + data.length);
        }
        long end = range.length().isPresent() ? range.offset() + range.length().getAsLong() : data.length;
        return Arrays.copyOfRange(data, (int) range.offset(), (int) Math.min(end, data.length));
    }

    private static byte[] read(BlobContent.Sized content) {
        if (content instanceof BlobContent.OfBytes bytes) {
            return bytes.bytes().clone();
        }
        try (InputStream in = content.openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ETag newETag() {
        return new ETag(UUID.randomUUID().toString());
    }
}
