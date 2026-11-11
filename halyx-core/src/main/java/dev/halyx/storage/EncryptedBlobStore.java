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
package dev.halyx.storage;

import dev.halyx.capability.Capabilities;
import dev.halyx.capability.Capability;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedHeader;

import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A blob store that seals blobs with a key before they leave the application and opens them on the way back: the
 * view {@link BlobStore#encryptedWith} returns. Each blob is HLXS sealed data whose AAD is the blob key, so a blob
 * can't be moved to another key unnoticed, while replication to another bucket keeps it readable.
 *
 * <p>With a prefix, only blob keys under it are sealed; the rest pass through as plain blobs. Sizes from
 * {@link #head} and {@link #get} are the plaintext's; sizes from {@link #list} are the stored, sealed sizes (D22).
 */
final class EncryptedBlobStore implements BlobStore {

    /** Marks a sealed blob; its value names the format. */
    static final String MARKER = "halyx_encryption";
    static final String FORMAT = "hlxs1";
    /** The plaintext's {@code Content-Encoding}, kept out of the real header so nothing decompresses the ciphertext. */
    static final String CONTENT_ENCODING = "halyx_content_encoding";
    /** The plaintext length when it was known at write time, so {@link #head} needs no read. */
    static final String PLAINTEXT_LENGTH = "halyx_plaintext_length";
    private static final String RESERVED = "halyx_";
    /**
     * The first read of a ranged get: a header with one key is a few hundred bytes, so this fits several keys in one
     * small read, and grows when a header is longer.
     */
    private static final int HEADER_PROBE = 4 * 1024;

    private final BlobStore delegate;
    private final Function<BlobKey, CryptoKey> keys;
    private final KeyStore opener;
    private final String prefix;

    EncryptedBlobStore(BlobStore delegate, Function<BlobKey, CryptoKey> keys, KeyStore opener, String prefix) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.prefix = prefix;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String bucket() {
        return delegate.bucket();
    }

    private boolean sealed(BlobKey key) {
        return prefix == null || key.value().startsWith(prefix);
    }

    private static byte[] aad(BlobKey key) {
        return key.value().getBytes(StandardCharsets.UTF_8);
    }

    // Writes

    @Override
    public PutResult put(BlobKey key, BlobContent content, PutOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(options, "options");
        if (!sealed(key)) {
            return delegate.put(key, content, options);
        }
        long length = content instanceof BlobContent.Sized sized ? sized.length() : -1;
        return delegate.write(key, sealing(key, out -> {
            try (InputStream in = content.openStream()) {
                in.transferTo(out);
            }
        }), sealedOptions(options, length));
    }

    @Override
    public PutResult write(BlobKey key, BlobSink sink, PutOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(options, "options");
        if (!sealed(key)) {
            return delegate.write(key, sink, options);
        }
        return delegate.write(key, sealing(key, sink), sealedOptions(options, -1));
    }

    /** A sink that seals what {@code plaintext} writes into the store's own stream, which it leaves open. */
    private BlobSink sealing(BlobKey key, BlobSink plaintext) {
        CryptoKey key0 = keys.apply(key);
        return out -> {
            try (OutputStream sealing = key0.encryptingStream(new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    flush(); // the store closes its own stream
                }
            }, aad(key))) {
                plaintext.writeTo(sealing);
            }
        };
    }

    /**
     * The options the store writes with: the caller's, with the marker added, and {@code Content-Encoding} moved into
     * the metadata, since a store or CDN that saw {@code gzip} on the ciphertext could decompress or re-encode it.
     */
    private static PutOptions sealedOptions(PutOptions options, long plaintextLength) {
        requireUnreserved(options.metadata());
        PutOptions.Builder b = PutOptions.builder()
                .headers(encoding(options.headers(), null))
                .metadata(options.metadata())
                .metadata(MARKER, FORMAT)
                .partSize(options.partSize())
                .uploadConcurrency(options.uploadConcurrency());
        options.headers().contentEncoding().ifPresent(encoding -> b.metadata(CONTENT_ENCODING, encoding));
        if (plaintextLength >= 0) {
            b.metadata(PLAINTEXT_LENGTH, Long.toString(plaintextLength));
        }
        options.ifMatch().ifPresent(b::ifMatch);
        if (options.ifNotExists()) {
            b.ifNotExists();
        }
        options.tier().ifPresent(b::tier);
        options.serverSideKey().ifPresent(b::serverSideKey);
        if (options.validateChecksum()) {
            b.validateChecksum();
        }
        return b.build();
    }

    private static void requireUnreserved(Map<String, String> metadata) {
        for (String name : metadata.keySet()) {
            if (name.regionMatches(true, 0, RESERVED, 0, RESERVED.length())) {
                throw new InvalidArgumentException("Metadata names starting with '" + RESERVED + "' are reserved for "
                        + "encrypted blobs (got '" + name + "')");
            }
        }
    }

    // Reads

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        if (!sealed(key)) {
            return delegate.get(key, options);
        }
        if (options.range().isPresent()) {
            return getRange(key, options, options.range().get());
        }
        Blob stored = delegate.get(key, options);
        try {
            BlobMetadata metadata = stored.metadata();
            requireSealed(key, metadata);
            InputStream in = stored.content();
            SealedHeader header = SealedHeader.read(in);
            long plaintext = header.plaintextLength(metadata.size());
            InputStream opened = opener.decryptingStream(header, in, metadata.size(), 0, plaintext, aad(key));
            return new Blob(plain(metadata, plaintext), opened);
        } catch (RuntimeException e) {
            stored.close();
            throw e;
        }
    }

    /**
     * Reads the header with a small ranged read, which also gives the stored size and version, then only the segments
     * the range touches, pinned to that version so a concurrent overwrite can't mix two blobs. When those segments are
     * within the bytes the first read fetched, as for a small blob, there's no second read.
     */
    private Blob getRange(BlobKey key, GetOptions options, ByteRange range) {
        Probe probe = probe(key, options.ifMatch());
        long total = probe.plaintext;
        if (range.offset() >= total && total > 0 || range.offset() > total) {
            throw new InvalidArgumentException("Range starting at " + range.offset() + " is beyond the end of blob "
                    + key + " (" + total + " bytes)");
        }
        long length = Math.min(range.length().orElse(total - range.offset()), total - range.offset());
        long start = probe.header.segmentStart(range.offset());
        long end = probe.header.segmentEnd(range.offset() + length, probe.metadata.size());
        if (end <= probe.bytes.length) {
            InputStream fetched = new ByteArrayInputStream(probe.bytes, (int) start, (int) (end - start));
            return new Blob(plain(probe.metadata, total), opener.decryptingStream(probe.header, fetched,
                    probe.metadata.size(), range.offset(), length, aad(key)));
        }
        GetOptions.Builder segments = GetOptions.builder().range(ByteRange.of(start, end - start))
                .ifMatch(probe.metadata.etag());
        Blob stored = delegate.get(key, segments.build());
        try {
            InputStream opened = opener.decryptingStream(probe.header, stored.content(), probe.metadata.size(),
                    range.offset(), length, aad(key));
            return new Blob(plain(probe.metadata, total), opened);
        } catch (RuntimeException e) {
            stored.close();
            throw e;
        }
    }

    /** What the first read of a ranged get found: the stored metadata, the header, and the bytes it fetched. */
    private record Probe(BlobMetadata metadata, SealedHeader header, long plaintext, byte[] bytes) {
    }

    /** The stored metadata and the header, from the start of the blob; reads more if the header is longer. */
    private Probe probe(BlobKey key, Optional<ETag> ifMatch) {
        int length = HEADER_PROBE;
        while (true) {
            GetOptions.Builder first = GetOptions.builder().range(ByteRange.of(0, length));
            ifMatch.ifPresent(first::ifMatch);
            try (Blob start = delegate.get(key, first.build())) {
                BlobMetadata metadata = start.metadata();
                requireSealed(key, metadata);
                byte[] bytes = start.readAllBytes();
                try {
                    SealedHeader header = SealedHeader.read(new ByteArrayInputStream(bytes));
                    return new Probe(metadata, header, header.plaintextLength(metadata.size()), bytes);
                } catch (DataIntegrityException e) {
                    if (metadata.size() <= length) {
                        throw e; // the whole blob was read: it really is truncated
                    }
                    length = (int) Math.min(metadata.size(), (long) length * 4);
                    ifMatch = Optional.of(metadata.etag());
                }
            }
        }
    }

    @Override
    public BlobMetadata download(BlobKey key, Path target, GetOptions options) {
        Objects.requireNonNull(target, "target");
        if (!sealed(key)) {
            return delegate.download(key, target, options);
        }
        Path absolute = target.toAbsolutePath();
        try (Blob blob = get(key, options)) {
            Path directory = absolute.getParent();
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "." + absolute.getFileName(), ".part");
            try {
                try (InputStream in = blob.content()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return blob.metadata();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not download " + key + " to " + target, e);
        }
    }

    /**
     * The plaintext's metadata. Exact without a read when the plaintext length was recorded at write time; otherwise
     * the header is read. A blob under the encrypted scope that isn't sealed is reported as stored.
     */
    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        Optional<BlobMetadata> stored = delegate.head(key);
        if (stored.isEmpty() || !sealed(key) || !FORMAT.equals(stored.get().userMetadata().get(MARKER))) {
            return stored;
        }
        String recorded = stored.get().userMetadata().get(PLAINTEXT_LENGTH);
        if (recorded != null) {
            try {
                return Optional.of(plain(stored.get(), Long.parseLong(recorded)));
            } catch (NumberFormatException e) {
                // not ours: read the header instead
            }
        }
        Probe probe = probe(key, Optional.of(stored.get().etag()));
        return Optional.of(plain(probe.metadata, probe.plaintext));
    }

    @Override
    public boolean exists(BlobKey key) {
        return delegate.exists(key);
    }

    private static void requireSealed(BlobKey key, BlobMetadata metadata) {
        if (!FORMAT.equals(metadata.userMetadata().get(MARKER))) {
            throw new InvalidArgumentException("Blob " + key + " isn't encrypted, but it's read through an encrypted "
                    + "view: it was written without the key, or by another tool");
        }
    }

    /**
     * Stored metadata as the caller sees it: the plaintext size, its own headers, and no markers or checksums. The
     * stored checksums are the ciphertext's, which a caller can't compare with anything it holds; each segment's tag
     * authenticates the plaintext instead.
     */
    private static BlobMetadata plain(BlobMetadata stored, long plaintextLength) {
        Map<String, String> user = new LinkedHashMap<>();
        stored.userMetadata().forEach((name, value) -> {
            if (!name.regionMatches(true, 0, RESERVED, 0, RESERVED.length())) {
                user.put(name, value);
            }
        });
        BlobHeaders headers = encoding(stored.headers(), stored.userMetadata().get(CONTENT_ENCODING));
        return new BlobMetadata(stored.key(), plaintextLength, stored.etag(), stored.lastModified(), headers, user,
                stored.tier(), Checksums.none());
    }

    /** {@code headers} with {@code Content-Encoding} set to {@code value}, or without one for null. */
    private static BlobHeaders encoding(BlobHeaders headers, String value) {
        return new BlobHeaders(headers.contentType(), Optional.ofNullable(value), headers.contentDisposition(),
                headers.contentLanguage(), headers.cacheControl());
    }

    // Everything else

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        delegate.delete(key, options);
    }

    @Override
    public BatchDeleteResult deleteAll(Collection<BlobKey> keys) {
        return delegate.deleteAll(keys);
    }

    /** Keeps the markers through an update, which otherwise replaces every metadata entry. */
    @Override
    public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!sealed(key)) {
            return delegate.updateMetadata(key, update);
        }
        requireUnreserved(update.metadata());
        BlobMetadata stored = delegate.head(key).orElseThrow(() -> new NotFoundException(
                "Blob " + key + " not found", null, provider(), null));
        MetadataUpdate.Builder b = MetadataUpdate.builder()
                .headers(encoding(update.headers(), null))
                .metadata(update.metadata());
        stored.userMetadata().forEach((name, value) -> {
            if (name.equals(MARKER) || name.equals(PLAINTEXT_LENGTH)) {
                b.metadata(name, value);
            }
        });
        update.headers().contentEncoding().ifPresent(encoding -> b.metadata(CONTENT_ENCODING, encoding));
        update.ifMatch().ifPresent(b::ifMatch);
        BlobMetadata updated = delegate.updateMetadata(key, b.build());
        if (!FORMAT.equals(updated.userMetadata().get(MARKER))) {
            return updated;
        }
        String recorded = updated.userMetadata().get(PLAINTEXT_LENGTH);
        return recorded != null ? plain(updated, Long.parseLong(recorded))
                : head(key).orElse(updated);
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        delegate.setTier(key, tier);
    }

    /** Stored sizes, not plaintext ones, for sealed blobs (D22): a listing never sees a blob's header. */
    @Override
    public BlobPage list(ListOptions options) {
        return delegate.list(options);
    }

    /**
     * Through the application whenever either side is sealed: the AAD binds a blob to its key, so the store can't copy
     * it, and data moving in or out of the encrypted scope is sealed or opened on the way.
     */
    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        if (!sealed(source) && !sealed(target)) {
            return delegate.copy(source, target);
        }
        try (Blob blob = get(source)) {
            BlobMetadata metadata = blob.metadata();
            PutOptions options = PutOptions.builder().headers(metadata.headers()).metadata(metadata.userMetadata())
                    .build();
            return put(target, BlobContent.ofStream(blob.content(), metadata.size()), options);
        }
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        refusePresigned(key);
        return delegate.presign(key, request);
    }

    @Override
    public PresignedUpload startPresignedUpload(BlobKey key, PutOptions options) {
        refusePresigned(key);
        return delegate.startPresignedUpload(key, options);
    }

    @Override
    public PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) {
        refusePresigned(upload.key());
        return delegate.presignUploadPart(upload, partNumber, expiry);
    }

    @Override
    public PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) {
        refusePresigned(upload.key());
        return delegate.completePresignedUpload(upload, parts);
    }

    @Override
    public void abortPresignedUpload(PresignedUpload upload) {
        delegate.abortPresignedUpload(upload);
    }

    /** A presigned URL would let a client write plaintext, or read ciphertext, past the key. */
    private void refusePresigned(BlobKey key) {
        if (sealed(key)) {
            throw new UnsupportedFeatureException("Presigned URLs bypass client-side encryption, so blob " + key
                    + " can't have one: for presigned uploads and downloads of encrypted data, use server-side "
                    + "encryption with a key instead (S3 SSE-KMS, GCS CMEK, Azure encryption scopes)", null,
                    provider(), null);
        }
    }

    /**
     * The store's capabilities, less server-side copy, which the AAD rules out, and checksums, which would describe the
     * ciphertext: the segments' tags authenticate every read, and {@code validateChecksum} still protects the upload of
     * the stored bytes. A whole-store view also drops presigned URLs; a prefix view keeps them for the keys outside its
     * prefix.
     */
    @Override
    public Capabilities capabilities() {
        Set<Capability> kept = new HashSet<>(delegate.capabilities().asSet());
        kept.remove(StorageCapability.SERVER_SIDE_COPY);
        kept.remove(StorageCapability.CHECKSUM);
        if (prefix == null) {
            kept.remove(StorageCapability.PRESIGNED_URL);
            kept.remove(StorageCapability.PRESIGNED_MULTIPART);
        }
        return Capabilities.of(kept.toArray(Capability[]::new));
    }

    /** The same view over the store's server-side key view: sealed by Halyx, then encrypted again at rest. */
    @Override
    public BlobStore withServerSideKey(CryptoKey key) {
        return new EncryptedBlobStore(delegate.withServerSideKey(key), keys, opener, prefix);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return delegate.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "EncryptedBlobStore[" + delegate + (prefix == null ? "" : ", prefix " + prefix) + "]";
    }
}
