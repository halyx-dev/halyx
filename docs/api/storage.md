---
title: Object storage
parent: API guide
nav_order: 1
---

# Object storage: `BlobStore`
{: .no_toc }

One API for **Amazon S3**, **Azure Blob Storage** (block blobs) and **Google Cloud Storage**, plus an
in-memory store for tests. A `BlobStore` is one bucket or container.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

```java
BlobStore store = halyx.provider("aws").blobStore("acme-invoices");
```

## Basics

```java
BlobKey key = BlobKey.of("invoices/2026/42.pdf");

// Write: from bytes, a string, a file or a stream
store.put(key, BlobContent.ofFile(Path.of("42.pdf")));
store.put(BlobKey.of("notes.txt"), BlobContent.ofString("hello"));
store.put(BlobKey.of("upload.bin"), BlobContent.ofStream(inputStream));          // length unknown
store.put(BlobKey.of("upload2.bin"), BlobContent.ofStream(inputStream, length)); // length known

// Read: a Blob is an InputStream plus its metadata; always close it
try (Blob blob = store.get(key)) {
    BlobMetadata meta = blob.metadata();   // size, ETag, last modified, headers, user metadata
    blob.content().transferTo(out);
}
byte[] small = store.getBytes(BlobKey.of("notes.txt"));

// Metadata without the content
Optional<BlobMetadata> meta = store.head(key);
boolean present = store.exists(key);

// Copy (server-side where the provider supports it) and delete (idempotent)
store.copy(key, BlobKey.of("archive/2026/42.pdf"));
store.delete(key);
```

A missing blob is a `NotFoundException` on `get`, and an empty `Optional` on `head`. `delete` of a
missing key succeeds.

## Put options

`PutOptions` controls how a blob is written:

```java
store.put(key, BlobContent.ofFile(pdf), PutOptions.builder()
        .contentType("application/pdf")
        .contentDisposition("attachment; filename=\"invoice-42.pdf\"")
        .cacheControl("private, max-age=3600")
        .metadata("customer_id", "acme")      // user metadata
        .tier(StorageTier.COOL)
        .ifNotExists()                         // create-only
        .validateChecksum()
        .build());
```

| Option | Meaning |
|---|---|
| `contentType`, `contentEncoding`, `contentDisposition`, `contentLanguage`, `cacheControl` | HTTP headers stored with the blob and returned in `BlobMetadata.headers()`. They also apply when the blob is served through presigned URLs or a CDN. |
| `metadata(name, value)` | User metadata. For names that work on every cloud, use lower-case letters, digits and `_`, starting with a letter: Azure rejects `-`, and S3 lower-cases names. |
| `ifNotExists()` | Fail with `PreconditionFailedException` if the blob already exists. |
| `ifMatch(etag)` | Compare-and-swap: write only if the blob still has this ETag. |
| `tier(StorageTier)` | The storage class (see [storage tiers](#storage-tiers)). |
| `validateChecksum()` | Have the provider verify a checksum before committing (see [checksums](#checksums)). |
| `partSize(bytes)`, `uploadConcurrency(n)` | Tune [large uploads](#large-and-streamed-uploads). |

Bytes are stored and returned exactly as uploaded. A `gzip`-encoded blob comes back compressed on every
provider, because GCS decompressive transcoding is turned off.

## Conditional writes and deletes

Every provider supports create-only and compare-and-swap writes, with the same semantics. On GCS the
`ETag` is the object generation, so `ifMatch` behaves the same everywhere:

```java
PutResult v1 = store.put(key, content, PutOptions.builder().ifNotExists().build());
try {
    store.put(key, updated, PutOptions.builder().ifMatch(v1.etag()).build());
} catch (PreconditionFailedException e) {
    // someone else changed the blob first: reread and retry
}

store.delete(key, DeleteOptions.ifMatch(v1.etag()));   // fails if the blob changed or is gone
```

## Large and streamed uploads

`write` streams a blob of any size, in parts. You write to an `OutputStream`; Halyx splits it into parts
and uploads them in parallel:

```java
store.write(BlobKey.of("exports/orders.csv.gz"), out -> {
    try (var gzip = new GZIPOutputStream(out)) {
        orders.forEach(order -> writeCsvLine(gzip, order));
    }
}, PutOptions.builder().contentType("text/csv").contentEncoding("gzip").build());
```

- Parts are 8 MiB by default (`partSize`). Up to `uploadConcurrency` parts upload at once (4 by default,
  at most 32), on virtual threads.
- Writing blocks while every slot is busy, so memory stays at about `(uploadConcurrency + 1) × partSize`
  whatever the blob size. Part buffers come from a JVM-wide pool (at most `halyx.buffer-pool.max-bytes`
  idle, 256 MiB by default) instead of being allocated per part.
- The blob is committed only if the callback returns normally. If it throws, the upload is aborted and
  any existing blob is left untouched. The first failed part aborts the whole upload.
- Content that fits in one part is sent as a single request.

| Provider | Upload in parts | Large known-length `put` |
|---|---|---|
| AWS | S3 multipart upload, parts in parallel (at least 5 MiB, up to 10,000 parts) | parallel multipart above `aws.s3.multipart-threshold` (64 MiB) |
| Azure | staged blocks in parallel, then Put Block List | SDK parallel block upload, block size = part size |
| GCP | resumable upload session (sequential by protocol) | resumable upload, chunk size = part size |

## Parallel downloads and ranges

```java
// Download to a file: 4 concurrent 8 MiB ranges by default
store.download(key, Path.of("/data/model.bin"));

// Or read an ordinary InputStream filled by 8 concurrent ranged reads
GetOptions options = GetOptions.builder().parallelism(8).partSize(16 * 1024 * 1024).validateChecksum().build();
try (Blob blob = store.get(key, options)) {
    blob.content().transferTo(out);
}

// Part of a blob
try (Blob header = store.get(key, GetOptions.builder().range(ByteRange.of(0, 1024)).build())) { ... }
```

- With `parallelism` above 1, Halyx looks the blob up once, then fetches byte ranges concurrently on
  virtual threads, all pinned to that version (ETag or generation), and hands them to you in order.
  Memory stays at about `parallelism × partSize`, using pooled buffers.
- A blob that changes mid-download fails with `PreconditionFailedException` instead of mixing
  versions. A failed range is retried on its own when a retry interceptor is configured.
- `download` writes to a temporary file next to the target and moves it into place, so the target only
  ever holds a complete blob.
- `ByteRange.of(offset, length)` reads part of a blob; `ByteRange.from(offset)` reads to the end.

**Round trips.** `get` costs one request on S3 and Azure: the metadata comes from the download response.
On GCS, `get` looks up metadata first and pins the download to that generation. If an Azure download
drops mid-stream, it resumes from the last byte, pinned to the same ETag.

## Listing

```java
// One page at a time: "folders" through a delimiter
BlobPage page = store.list(ListOptions.builder().prefix("photos/").delimiter("/").pageSize(500).build());
page.items();           // blobs directly under photos/
page.prefixes();        // "sub-directories": photos/2024/, photos/2025/
page.nextPageToken();   // pass to ListOptions.pageToken(...) for the next page

// Everything under a prefix, as a lazy stream
try (Stream<BlobSummary> blobs = store.listAll("photos/2026/")) {
    blobs.forEach(summary -> process(summary.key(), summary.size()));
}
```

Listing is lexicographic on every provider, with opaque page tokens. `listAll` fetches the next page in
the background while you work through the current one, so long listings wait for the network about
once per page.

## Batch delete

```java
BatchDeleteResult result = store.deleteAll(keys);
result.deleted();      // keys deleted, in your order (missing keys count as deleted)
result.failed();       // Map<BlobKey, CloudException>: per-key failures, never thrown
result.isComplete();
```

`deleteAll` splits the keys into each provider's batch limit: S3 `DeleteObjects` (1,000), Azure Blob
Batch (256) and GCS JSON batch (100). Up to 4 batches are in flight at once. Where an endpoint doesn't
implement batching, Halyx falls back to single deletes, 16 at a time.

## Metadata and headers

Replace headers and user metadata without re-uploading:

```java
BlobMetadata current = store.head(key).orElseThrow();
store.updateMetadata(key, MetadataUpdate.from(current)
        .cacheControl("no-cache")
        .metadata("reviewed", "true")
        .removeMetadata("draft")
        .ifMatch(current.etag())
        .build());
```

S3 does this as a server-side copy onto itself (up to 5 GiB, storage class preserved). Azure uses Set
Blob Properties plus Set Blob Metadata, chained by ETag. GCS uses a generation-matched patch.

## Checksums

```java
store.put(key, BlobContent.ofFile(path), PutOptions.builder().validateChecksum().build());
try (Blob blob = store.get(key, GetOptions.builder().validateChecksum().build())) { ... }
```

Halyx computes only the algorithm each provider checks and stores: **CRC32C** on S3 and GCS
(hardware-accelerated in the JDK), and **MD5** on Azure. The provider verifies it before committing.
Halyx then compares the checksums the provider reports for the stored blob. A mismatch throws
`DataIntegrityException`, and the corrupt blob is deleted if it is still the version just written.
Downloads, including parallel ones, are verified at the end of the stream against the strongest stored
checksum (`BlobMetadata.checksums()`).

## Storage tiers

| `StorageTier` | AWS S3 | Azure | GCS |
|---|---|---|---|
| `HOT` | STANDARD | Hot | STANDARD |
| `COOL` | STANDARD_IA | Cool | NEARLINE |
| `COLD` | GLACIER_IR | Cold | COLDLINE |
| `ARCHIVE` | GLACIER | Archive | ARCHIVE |

```java
store.put(key, content, PutOptions.builder().tier(StorageTier.COOL).build());
store.setTier(key, StorageTier.ARCHIVE);
Optional<StorageTier> tier = store.head(key).orElseThrow().tier();
```

`ARCHIVE` blobs on S3 and Azure are offline until restored (restoring isn't in the API yet); on GCS
they stay readable. On GCS, changing the tier rewrites the object, which changes its ETag. On Azure, `get`
metadata carries no tier, so use `head` for it.

## Presigned URLs

Let browsers, mobile apps or other services upload or download a blob directly, without cloud
credentials and without streaming the bytes through your service:

```java
PresignedUrl upload = store.presignUpload(BlobKey.of("avatars/42.png"), Duration.ofMinutes(15), "image/png");
// Return upload.uri(), upload.method() and upload.headers() to the client, which sends
// PUT <uri> with exactly those headers and the file as the body.

PresignedUrl download = store.presignDownload(BlobKey.of("avatars/42.png"), Duration.ofHours(1));
```

Always pass `headers()` along with the URL. Azure requires `x-ms-blob-type: BlockBlob` on uploads, and
S3 and GCS sign the `Content-Type`, so the client must send exactly that value. Expiry is capped at 7
days, the longest every provider allows.

| Provider | Signing | Requirement |
|---|---|---|
| AWS | SigV4 query signature | any credentials |
| Azure | service SAS, or user-delegation SAS with Entra ID | a shared key or an Entra ID identity |
| GCP | V4 signed URL | credentials that can sign: a service account key, workload identity or impersonation |

`capabilities().supports(StorageCapability.PRESIGNED_URL)` reflects the configured credentials: on GCP it
is false for user or anonymous credentials, which can't sign.

## Presigned multipart uploads

For files too big for one presigned PUT (5 GiB on S3), or to let a browser upload parts in parallel and
resume after a failure, hand out one URL per part. The bytes never pass through your service:

```java
// Server: start the upload and presign the parts
PresignedUpload upload = store.startPresignedUpload(key, PutOptions.builder().contentType("video/mp4").build());
List<PresignedUrl> urls = IntStream.rangeClosed(1, partCount)
        .mapToObj(n -> store.presignUploadPart(upload, n, Duration.ofHours(1)))
        .toList();

// Client: PUT each part (at least 5 MiB, except the last) and report back each response's ETag

// Server: assemble the parts into the blob, or abort
store.completePresignedUpload(upload, List.of(new UploadedPart(1, etag1), new UploadedPart(2, etag2)));
store.abortPresignedUpload(upload);
```

| Provider | Upload | Part URL | Complete |
|---|---|---|---|
| AWS | S3 multipart upload | presigned UploadPart | CompleteMultipartUpload (needs part ETags) |
| Azure | blocks staged against the blob | SAS URL for Put Block | Put Block List (ETags not needed) |
| GCP | XML API multipart upload | V4-signed UploadPart | CompleteMultipartUpload (needs part ETags) |

- Browsers can read the `ETag` response header only if the bucket's CORS rules expose it (S3
  `ExposeHeaders: ETag`, GCS `responseHeader: ["ETag"]`).
- Headers, metadata and tier from the options apply to the finished blob. Conditional writes and
  checksum validation are rejected, because Halyx never sees the bytes.
- The `uploadId` is opaque. On Azure it encodes the blob's headers and metadata, so keep it
  server-side or treat it as untrusted input.
- Abandoned uploads cost storage until cleaned up: add a lifecycle rule that aborts incomplete multipart
  uploads on S3 and GCS. Azure discards uncommitted blocks after a week.

## Performance

**Connection pooling.** Each provider connection shares one pooled HTTP client across its services. Tune
it with provider properties (see [Provider configuration](../providers.md#http-connections)).

**Throttling.** Throttling surfaces as `ThrottledException`, with `retryAfter()` when the service says
how long to wait. AWS clients default to the SDK's adaptive retry mode, which backs off while S3 returns
503 SlowDown (`aws.retry-mode`).

**Key design on S3.** S3 limits request rates per key prefix: about 3,500 writes and 5,500 reads per
second. Spread hot keys across prefixes rather than one sequential prefix:

```java
String shard = Integer.toHexString(Math.floorMod(orderId.hashCode(), 16));   // 0..f
BlobKey key = BlobKey.of("orders/" + shard + "/2026-10-10/" + orderId + ".json");
```

Azure and GCS scale per account or bucket rather than per prefix, but they still ramp up gradually, so
increase the load on a new bucket over minutes, not seconds.

See [benchmarks](../design.md#benchmarks) for what parallel transfers, batching and prefetching gain.

## Asynchronous calls

`async()` gives a store's asynchronous view: the same operations as `CompletableFuture`s, for code that
mustn't wait, without Reactor.

```java
AsyncBlobStore async = invoices.async();

async.put(BlobKey.of("a.pdf"), BlobContent.ofBytes(pdf))
        .thenCompose(put -> async.head(BlobKey.of("a.pdf")))
        .thenAccept(head -> log.info("stored {} bytes", head.orElseThrow().size()));
```

| | |
|---|---|
| Operations | `put` (content in memory is sent without blocking; files and streams are read on a virtual thread), `getBytes`, `head`, `exists`, `delete` and `list`. `blocking()` returns the store for the rest |
| Non-blocking on | Amazon S3 (`S3AsyncClient` over Netty, created on the first asynchronous call). Elsewhere the blocking call runs on a virtual thread, with the same results |
| Your own threads | `async(executor)` runs any blocking work (a call without a non-blocking path, a blocking interceptor, reading a file) on your executor instead; the reactive views pass their scheduler, with the subscriber's context restored |
| Same as blocking | argument and capability checks, interceptors, error translation: a failed future holds the `CloudException` the blocking call would throw |
| Retries | `RetryInterceptor` waits on a timer, not a thread; cancelling the future stops retrying |
| Your interceptors | apply as they are: one that only implements `intercept` runs on a virtual thread around the call. Override `interceptAsync` to stay off threads too |

The reactive views use it, so a `ReactiveBlobStore` is non-blocking for these calls where the provider is.

## Capabilities and native clients

All three clouds support every `StorageCapability`. Where support depends on credentials, as with
presigning on GCP, `capabilities()` says so:

```java
if (store.capabilities().supports(StorageCapability.PRESIGNED_URL)) { ... }

S3Client s3 = store.unwrap(S3Client.class);                    // AWS (also S3Presigner)
BlobContainerClient container = store.unwrap(BlobContainerClient.class);   // Azure (also BlobServiceClient)
Storage gcs = store.unwrap(Storage.class);                      // Google Cloud
```

See [Capabilities and native clients](capabilities-and-unwrap.md).
