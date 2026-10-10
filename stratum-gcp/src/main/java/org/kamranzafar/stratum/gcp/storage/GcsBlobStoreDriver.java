package org.kamranzafar.stratum.gcp.storage;

import com.google.auth.ServiceAccountSigner;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.Storage.CopyRequest;
import com.google.cloud.storage.Storage.SignUrlOption;
import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.NotFoundException;
import org.kamranzafar.stratum.error.PreconditionFailedException;
import org.kamranzafar.stratum.gcp.GcpErrors;
import org.kamranzafar.stratum.gcp.GcpProvider;
import org.kamranzafar.stratum.storage.Blob;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobHeaders;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobMetadata;
import org.kamranzafar.stratum.storage.BlobPage;
import org.kamranzafar.stratum.storage.BlobSummary;
import org.kamranzafar.stratum.storage.ETag;
import org.kamranzafar.stratum.storage.GetOptions;
import org.kamranzafar.stratum.storage.ListOptions;
import org.kamranzafar.stratum.storage.PresignRequest;
import org.kamranzafar.stratum.storage.PresignedUrl;
import org.kamranzafar.stratum.storage.PutOptions;
import org.kamranzafar.stratum.storage.PutResult;
import org.kamranzafar.stratum.storage.StorageCapability;
import org.kamranzafar.stratum.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.stratum.storage.spi.MultipartUploadSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Blob storage on Google Cloud Storage.
 *
 * <p>The Stratum {@link ETag} of a GCS object is its <em>generation</em> number, not the HTTP etag,
 * because GCS write preconditions are expressed on generations. This keeps {@code ifMatch} working the
 * same way as on S3 and Azure.
 */
public final class GcsBlobStoreDriver extends AbstractBlobStoreDriver {

    private final Storage storage;
    private final boolean customEndpoint;

    public GcsBlobStoreDriver(String bucket, Storage storage, boolean customEndpoint) {
        super(GcpProvider.ID, bucket, capabilities(storage));
        this.storage = storage;
        this.customEndpoint = customEndpoint;
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options) {
        // createFrom uses a resumable upload in chunks of the part size, so large content is fine.
        try (InputStream in = content.openStream()) {
            com.google.cloud.storage.Blob written = storage.createFrom(blobInfo(key, options), in,
                    options.partSize(), writeConditions(options));
            return new PutResult(key, etagOf(written));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        return new GcsResumableUpload(storage, blobInfo(key, options), writeConditions(options), key);
    }

    private BlobInfo blobInfo(BlobKey key, PutOptions options) {
        Long expectedGeneration = options.ifNotExists() ? Long.valueOf(0L)
                : options.ifMatch().map(etag -> generation(key, etag)).orElse(null);
        BlobInfo.Builder info = BlobInfo.newBuilder(BlobId.of(bucket, key.value(), expectedGeneration));
        var headers = options.headers();
        headers.contentType().ifPresent(info::setContentType);
        headers.contentEncoding().ifPresent(info::setContentEncoding);
        headers.contentDisposition().ifPresent(info::setContentDisposition);
        headers.contentLanguage().ifPresent(info::setContentLanguage);
        headers.cacheControl().ifPresent(info::setCacheControl);
        if (!options.metadata().isEmpty()) {
            info.setMetadata(options.metadata());
        }
        return info.build();
    }

    /** Generation 0 in the blob id means "must not exist"; any other value "must currently be this generation". */
    private static BlobWriteOption[] writeConditions(PutOptions options) {
        return options.isConditional()
                ? new BlobWriteOption[] {BlobWriteOption.generationMatch()}
                : new BlobWriteOption[0];
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        com.google.cloud.storage.Blob blob = storage.get(BlobId.of(bucket, key.value()));
        if (blob == null) {
            throw new NotFoundException("No blob " + bucket + "/" + key, null, provider, "notFound");
        }
        if (options.ifMatch().isPresent() && !options.ifMatch().get().equals(etagOf(blob))) {
            throw new PreconditionFailedException("Generation mismatch for " + key, null, provider, "conditionNotMet");
        }
        // Pin the generation we just read metadata for, so content and metadata always agree.
        // Raw bytes: never transcode gzip-encoded objects, matching S3 and Azure.
        ReadChannel reader = storage.reader(BlobId.of(bucket, key.value(), blob.getGeneration()),
                Storage.BlobSourceOption.shouldReturnRawInputStream(true));
        try {
            if (options.range().isPresent()) {
                var range = options.range().get();
                reader.seek(range.offset());
                if (range.length().isPresent()) {
                    reader = reader.limit(range.offset() + range.length().getAsLong());
                }
            }
        } catch (IOException e) {
            reader.close();
            throw new UncheckedIOException(e);
        }
        return new Blob(toMetadata(key, blob), Channels.newInputStream(reader));
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        return Optional.ofNullable(storage.get(BlobId.of(bucket, key.value()))).map(b -> toMetadata(key, b));
    }

    @Override
    public void delete(BlobKey key) {
        storage.delete(BlobId.of(bucket, key.value()));
    }

    @Override
    public BlobPage list(ListOptions options) {
        List<BlobListOption> query = new ArrayList<>();
        query.add(BlobListOption.pageSize(options.pageSize()));
        options.prefix().ifPresent(p -> query.add(BlobListOption.prefix(p)));
        options.delimiter().ifPresent(d -> query.add(BlobListOption.delimiter(d)));
        options.pageToken().ifPresent(t -> query.add(BlobListOption.pageToken(t)));
        var page = storage.list(bucket, query.toArray(BlobListOption[]::new));
        List<BlobSummary> items = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        for (com.google.cloud.storage.Blob b : page.getValues()) {
            // With a delimiter, common prefixes come back as synthetic "directory" entries.
            if (b.isDirectory()) {
                prefixes.add(b.getName());
            } else {
                items.add(new BlobSummary(BlobKey.of(b.getName()), b.getSize(), etagOf(b), lastModified(b)));
            }
        }
        return new BlobPage(items, prefixes, Optional.ofNullable(page.getNextPageToken()));
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        var copied = storage.copy(CopyRequest.of(BlobId.of(bucket, source.value()), BlobId.of(bucket, target.value())))
                .getResult();
        return new PutResult(target, etagOf(copied));
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        var method = switch (request.method()) {
            case GET -> HttpMethod.GET;
            case PUT -> HttpMethod.PUT;
        };
        BlobInfo.Builder info = BlobInfo.newBuilder(bucket, key.value());
        List<SignUrlOption> signOptions = new ArrayList<>(List.of(SignUrlOption.httpMethod(method), SignUrlOption.withV4Signature()));
        Map<String, String> headers = new LinkedHashMap<>();
        request.contentType().ifPresent(ct -> {
            // The content type becomes part of the signature, so the uploader must send it unchanged.
            info.setContentType(ct);
            signOptions.add(SignUrlOption.withContentType());
            headers.put("Content-Type", ct);
        });
        if (customEndpoint) {
            signOptions.add(SignUrlOption.withHostName(storage.getOptions().getHost()));
        }
        Instant expiresAt = Instant.now().plus(request.expiry());
        var url = storage.signUrl(info.build(), request.expiry().toSeconds(), TimeUnit.SECONDS,
                signOptions.toArray(SignUrlOption[]::new));
        try {
            return new PresignedUrl(request.method(), url.toURI(), expiresAt, headers);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("GCS returned an invalid signed URL: " + url, e);
        }
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(storage);
    }

    /**
     * Signed URLs need credentials that can sign (service account key, workload identity or
     * impersonation). User credentials and anonymous access cannot, so PRESIGNED_URL is only
     * advertised when the configured credentials are a {@link ServiceAccountSigner}.
     */
    private static Capabilities capabilities(Storage storage) {
        Set<StorageCapability> supported = EnumSet.allOf(StorageCapability.class);
        if (!(storage.getOptions().getCredentials() instanceof ServiceAccountSigner)) {
            supported.remove(StorageCapability.PRESIGNED_URL);
        }
        return Capabilities.of(supported.toArray(StorageCapability[]::new));
    }

    private long generation(BlobKey key, ETag etag) {
        try {
            return Long.parseLong(etag.value());
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException("Not a GCS generation ETag for " + key + ": " + etag.value(),
                    e, provider, "conditionNotMet");
        }
    }

    private static ETag etagOf(com.google.cloud.storage.BlobInfo blob) {
        return new ETag(String.valueOf(blob.getGeneration()));
    }

    private static Instant lastModified(com.google.cloud.storage.BlobInfo blob) {
        return blob.getUpdateTimeOffsetDateTime() == null ? null : blob.getUpdateTimeOffsetDateTime().toInstant();
    }

    private static BlobMetadata toMetadata(BlobKey key, com.google.cloud.storage.BlobInfo blob) {
        return new BlobMetadata(key, blob.getSize() == null ? 0 : blob.getSize(), etagOf(blob), lastModified(blob),
                BlobHeaders.of(blob.getContentType(), blob.getContentEncoding(), blob.getContentDisposition(),
                        blob.getContentLanguage(), blob.getCacheControl()),
                blob.getMetadata());
    }
}
