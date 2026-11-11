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

## Client-side encryption

`encryptedWith(key)` gives a view of a store that seals every blob with a key before it leaves the application,
and opens it on the way back. The key service only ever sees a data key: the blob is sealed locally, in segments,
with AES-256-GCM (see [Keys and encryption](keys.md)).

```java
CryptoKey key = keys.key("receipts");
BlobStore receipts = halyx.provider("cloud").blobStore("acme-orders").encryptedWith(key);

receipts.put(BlobKey.of("o-1001/receipt.pdf"), BlobContent.ofFile(pdf));     // sealed: one wrap call
try (Blob blob = receipts.get(BlobKey.of("o-1001/receipt.pdf"))) { ... }       // opened: one unwrap call
```

Each blob is HLXS sealed data, bound to its blob key: a sealed blob copied or renamed to another key outside the view
fails to open, while replication to another bucket keeps it readable. Content type, the other headers and your
metadata are kept; `Content-Encoding` is stored in the metadata instead, so nothing between you and the store
decompresses or re-encodes the ciphertext, and comes back on reads. Metadata names starting with `halyx_` are
reserved for the view's own entries, such as the `halyx_encryption` marker.

| | |
|---|---|
| Reads | Whole reads, `download` and parallel reads stream through the view. A range read fetches the header with a small ranged read (4 KiB), then only the segments the range touches, pinned to the version it read first; a range of a small blob comes from that first read alone. Each read unwraps the blob's data key once, so for many small range reads of the same blobs, turn on the key store's `dataKeyCache` |
| Sizes | `head` and `get` report the plaintext size. **`list` reports the stored size**, which is larger: a header of about 100 bytes to a few KB, depending on the key, plus 16 bytes per segment (64 KiB by default). A listing never sees a blob's header, and reading each one would cost a request per blob, so for a blob's exact size call `head`. Totals summed from a listing are overstated, the more so for small blobs |
| `head` | Free when the size was known at write time (`put` of bytes, a file or a sized stream); after a streamed `write`, it reads the header once |
| Plain data | Reading a blob that isn't sealed through the view fails with `InvalidArgumentException`. Reading a sealed blob without the key returns the ciphertext, starting with `HLXS`, never plaintext |
| Presigned URLs | Dropped: a presigned upload would bypass the key, and a presigned download would hand out ciphertext. For presigned access to encrypted data, use [server-side encryption with a key](#server-side-encryption-with-a-key) |
| `copy` | Through the application: the blob is opened and sealed again for its new key, so it costs a download and an upload. `SERVER_SIDE_COPY` is dropped |
| Checksums | `CHECKSUM` is dropped: the stored checksums are the ciphertext's, which you can't compare with anything you hold. Each segment's tag authenticates every read instead, and `validateChecksum` still protects the upload of the stored bytes |
| Asynchronous calls | `async()` seals and opens too, on a virtual thread, since sealing streams the content |

**A bucket encrypted in part.** `encryptedWith(key, "private/")` seals only blob keys under the prefix; the rest pass
through as plain blobs, keep presigned URLs, and copy on the server when both sides are outside the prefix. A copy
into or out of the prefix is sealed or opened on the way.

**A key per blob.** `encryptedWith(keys, blobKey -> "tenant-" + tenantOf(blobKey))` names a key of a key store for
each blob, such as a key per tenant; any key of the store opens what any other sealed.

Each write wraps a new data key and each read unwraps one, so the key service sees a call per blob. On busy paths,
turn on the key store's `dataKeyReuse` and `dataKeyCache` (see [Performance and caches](keys.md#performance-and-caches)).

## Server-side encryption with a key

`withServerSideKey(key)` gives a view of a store whose writes the storage service encrypts at rest with a key from
the same cloud's key service: S3 SSE-KMS, a customer-managed key on GCS, or an Azure encryption scope. The
service decrypts on read, so reads, ranges and presigned downloads work as usual. Presigned uploads work too, and
they are encrypted with the key.

```java
CryptoKey key = halyx.provider("cloud").keyStore().key("receipts");
BlobStore receipts = halyx.provider("cloud").blobStore("acme-orders").withServerSideKey(key);

receipts.put(BlobKey.of("o-1001/receipt.pdf"), BlobContent.ofFile(pdf));      // encrypted at rest with the key
PresignedUrl upload = receipts.presignUpload(BlobKey.of("o-1002/scan.png"), Duration.ofMinutes(15));
```

For one write, `PutOptions.builder().serverSideKey(key)` does the same. It takes precedence over the view's key.

| | |
|---|---|
| Writes | Puts, streamed writes and uploads in parts, copies to the view, `updateMetadata` and `setTier` (where the provider rewrites the object), presigned uploads and presigned multipart uploads all use the key |
| Presigned uploads | The URL carries the key as headers the client must send: S3 signs `x-amz-server-side-encryption` and the key id, GCS signs `x-goog-encryption-kms-key-name`, and an Azure SAS names the scope. Send every header in `PresignedUrl.headers()` |
| Copies | S3 and GCS copy on the server with the key. Azure's server-side copy can't name a scope, so a copy through the view downloads and uploads instead. A copy through a store without the key gets the bucket's default encryption |
| Rewrites in place | On S3, changing metadata or the tier is a copy onto itself. Through any store, Halyx carries the object's own key over; without that, S3 would apply the bucket's default. GCS does the same for tier changes |
| Key service calls | None per write: the storage service uses the key. Halyx reads the key's metadata once, for its id, and keeps it with the key's other metadata |
| Checks | The key must be an encryption key of the store's own provider (`InvalidArgumentException` otherwise), and the store must support `SERVER_SIDE_KMS` |

**On Azure**, Blob Storage encrypts with the Key Vault key that an encryption scope is bound to, and requests name
the scope, not the key. Open the key with the scope's name as an option:

```java
CryptoKey key = keys.key("receipts", KeyOptions.builder()
        .property("azure.storage.encryption-scope", "receipts-scope").build());
```

**With client-side encryption.** The two views stack: `withServerSideKey(serverKey).encryptedWith(sealingKey)`
seals each blob in the application, then the storage service encrypts the sealed bytes at rest. Either order works.
Server-side encryption alone protects data at rest. Anyone the bucket and key policies allow can read the
plaintext. Client-side encryption keeps it from the storage service too.

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
| Non-blocking on | Amazon S3 (`S3AsyncClient` over Netty, created on the first asynchronous call) and Azure Blob Storage (the SDK's async client). Elsewhere the blocking call runs on a virtual thread, with the same results |
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
