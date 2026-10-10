# Stratum

A cloud-agnostic Java 21 API that gives microservices one consistent way to use storage, SQL,
search and cache across **AWS**, **Azure** and **GCP**.

```java
try (Stratum stratum = Stratum.builder()
        .register("orders", ProviderConfig.of("aws").region("eu-west-2"))
        .register("archive", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod"))
        .build()) {

    BlobStore orders = stratum.provider("orders").blobStore("orders-bucket");
    orders.put(BlobKey.of("invoices/42.pdf"), BlobContent.ofFile(path),
            PutOptions.builder().contentType("application/pdf").ifNotExists().build());

    try (Blob blob = orders.get(BlobKey.of("invoices/42.pdf"))) {
        blob.content().transferTo(out);
    } catch (NotFoundException e) {
        // same exception on S3, Azure Blob and GCS
    }
}
```

### Large and streamed uploads

`write` streams a blob of any size in parts (8 MiB by default, configurable with
`PutOptions.partSize`). Memory use stays at one part, whatever the blob size:

```java
store.write(BlobKey.of("exports/orders.csv.gz"), out -> {
    try (var gzip = new GZIPOutputStream(out)) {
        orders.forEach(order -> writeCsvLine(gzip, order));
    }
}, PutOptions.builder().contentType("text/csv").contentEncoding("gzip").build());
```

The blob is committed only if the callback returns normally. If it throws, the upload is aborted and
any existing blob is left untouched. Content that fits in one part is sent as a single request.
Streams of unknown length use the same path: `store.put(key, BlobContent.ofStream(inputStream))`.

| Provider | Upload in parts | Large known-length `put` |
|---|---|---|
| AWS | S3 multipart upload (parts of at least 5 MiB, up to 10,000 parts) | switches to multipart above `aws.s3.multipart-threshold` (64 MiB) |
| Azure | staged blocks + Put Block List | SDK parallel block upload, block size = part size |
| GCP | resumable upload session | resumable upload, chunk size = part size |

### Folder-style listing

```java
BlobPage page = store.list(ListOptions.builder().prefix("photos/").delimiter("/").build());
page.items();    // blobs directly under photos/
page.prefixes(); // "sub-directories": photos/2024/, photos/2025/
```

### HTTP headers

`Content-Type`, `Content-Encoding`, `Content-Disposition`, `Content-Language` and `Cache-Control`
are stored with the blob (`PutOptions`) and returned in `BlobMetadata.headers()`. They also apply when
the blob is served through presigned URLs or a CDN. Bytes are always stored and returned exactly as
uploaded: a `gzip`-encoded blob comes back compressed on every provider, because GCS decompressive
transcoding is turned off.

### Checksums

```java
store.put(key, BlobContent.ofFile(path), PutOptions.builder().validateChecksum().build());
try (Blob blob = store.get(key, GetOptions.builder().validateChecksum().build())) { ... }
```

No algorithm is stored by all three clouds, so Stratum computes **CRC32C and MD5** itself. Each
provider checks what it supports before committing (S3: CRC32C, including full-object CRC32C for
multipart uploads; Azure: MD5 per request and per block; GCS: CRC32C and MD5 match conditions).
Stratum then compares the checksums the provider reports for the stored blob. A mismatch throws
`DataIntegrityException`, and the corrupt blob is deleted if it is still the version just written.
Downloads are verified at end of stream against the strongest stored checksum (`BlobMetadata.checksums()`).

### Conditional and batch delete

```java
store.delete(key, DeleteOptions.ifMatch(etag));            // fails if the blob changed or is gone
BatchDeleteResult result = store.deleteAll(keys);           // per-key failures, never thrown
```

`deleteAll` splits keys into each provider's batch limit: S3 `DeleteObjects` (1,000), Azure Blob Batch
(256), GCS JSON batch (100). Missing keys count as deleted. If an endpoint doesn't implement the batch
API, Stratum falls back to deleting keys one at a time.

### Metadata updates

```java
BlobMetadata current = store.head(key).orElseThrow();
store.updateMetadata(key, MetadataUpdate.from(current).cacheControl("no-cache").ifMatch(current.etag()).build());
```

Replaces headers and user metadata without re-uploading. S3 does this as a server-side copy onto
itself (limited to 5 GiB, storage class preserved). Azure uses Set Blob Properties plus Set Blob
Metadata, chained by ETag and keeping the stored Content-MD5. GCS uses a generation-matched patch.

### Storage tiers

| `StorageTier` | AWS S3 | Azure | GCS |
|---|---|---|---|
| `HOT` | STANDARD | Hot | STANDARD |
| `COOL` | STANDARD_IA | Cool | NEARLINE |
| `COLD` | GLACIER_IR | Cold | COLDLINE |
| `ARCHIVE` | GLACIER | Archive | ARCHIVE |

Set it with `PutOptions.tier(...)`, change it with `setTier(key, tier)`, and read it from
`BlobMetadata.tier()` / `BlobSummary.tier()`. `ARCHIVE` blobs on S3 and Azure are offline until
restored (restoring isn't in the API yet); on GCS they stay readable. On GCS, changing the tier
rewrites the object, which changes its ETag.

### Presigned URLs

Let browsers, mobile apps or other services download or upload a blob directly, without cloud
credentials and without streaming the bytes through your service:

```java
PresignedUrl upload = store.presignUpload(BlobKey.of("avatars/42.png"), Duration.ofMinutes(15), "image/png");
// Return upload.uri(), upload.method() and upload.headers() to the client, which sends
// PUT <uri> with exactly those headers and the file as the body.

PresignedUrl download = store.presignDownload(BlobKey.of("avatars/42.png"), Duration.ofHours(1));
```

Always pass `headers()` along with the URL. Providers differ: Azure requires
`x-ms-blob-type: BlockBlob` on uploads, and S3 and GCS sign the `Content-Type`, so the client must
send exactly that value. Expiry is capped at 7 days, the longest every provider allows.

| Provider | Signing | Requirement |
|---|---|---|
| AWS | SigV4 query signature | any credentials |
| Azure | Service SAS, or user-delegation SAS with Entra ID | shared key or Entra ID identity |
| GCP | V4 signed URL | credentials that can sign (service account key, workload identity, impersonation) |

`capabilities().supports(PRESIGNED_URL)` reflects the configured credentials: on GCP it is false for
user or anonymous credentials, which cannot sign.

## Spring Boot

Add the starter plus the provider modules you use:

```xml
<dependency>
  <groupId>org.kamranzafar</groupId>
  <artifactId>stratum-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.kamranzafar</groupId>
  <artifactId>stratum-aws</artifactId>
</dependency>
```

```yaml
stratum:
  providers:
    orders:
      type: aws                    # aws | azure | gcp | memory
      region: eu-west-2
      # endpoint: http://localhost:4566
      # credentials: { type: static, id: ..., secret: ... }   # default | static | anonymous
      # properties: { aws.s3.path-style: true }
  blob-stores:
    invoices:                      # bean name
      bucket: acme-invoices        # defaults to the bean name
      # provider: orders           # optional when only one provider is configured
  retry:
    enabled: false                 # the SDKs already retry; enable for uniform retries
```

```java
@Service
class InvoiceService {
    InvoiceService(@Qualifier("invoices") BlobStore invoices) { ... }
}
```

What the starter provides:

| Feature | Details |
|---|---|
| `Stratum` bean | Built from `stratum.providers`, closed with the context. Define your own `Stratum` bean to replace it. |
| `BlobStore` beans | One per `stratum.blob-stores` entry, named after the entry. |
| Interceptors | Every `Interceptor` bean is applied in `@Order` order. Order of application, outermost first: observations, your interceptors, retries. |
| Customisation | `StratumBuilderCustomizer` beans can add plugins or registrations. |
| Observations | With Micrometer, every operation records a `stratum.operation` observation (timer + span) tagged with `stratum.service`, `stratum.operation`, `stratum.provider` and `error`. Disable with `stratum.observations.enabled=false`. |
| Health | A `stratum` health contributor with one indicator per blob store, which lists one item from the bucket. Disable with `management.health.stratum.enabled=false`. |
| Fail fast | Startup fails, naming the property, for an unknown provider type (with the module to add), an unknown or ambiguous `provider` reference, or incomplete static credentials. |
| IDE support | Configuration metadata is generated, so `stratum.*` keys autocomplete. |

**Supports Spring Boot 3.5.x and 4.x** (Java 21) with the same starter: your application's Boot
parent or BOM picks the Spring versions. The auto-configuration is compiled against Boot 3.5. Health
is wired with Actuator's `org.springframework.boot.actuate.health` on Boot 3, and with the new
`org.springframework.boot.health` module on Boot 4; exactly one of the two activates. The test suite
runs on both: `stratum-spring-boot-autoconfigure` tests on 3.5, and the unpublished
`stratum-spring-boot4-tests` module runs the same shared tests on 4.1.

## Modules

| Module | Purpose |
|---|---|
| `stratum-bom` | Import to align Stratum versions |
| `stratum-core` | Portable API, provider SPI, error model, interceptors, in-memory provider. No cloud SDKs (enforced) |
| `stratum-tck` | Conformance test kit every provider must pass |
| `stratum-aws` | AWS SDK v2: S3 |
| `stratum-azure` | Azure SDK: Blob Storage |
| `stratum-gcp` | Google Cloud: Cloud Storage |
| `stratum-spring-boot-autoconfigure` | Spring Boot auto-configuration, properties, health, observations |
| `stratum-spring-boot-starter` | The dependency Spring Boot applications add |

Add `stratum-core` plus the provider jars you need; providers are discovered with `ServiceLoader`.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.kamranzafar</groupId>
      <artifactId>stratum-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Design

```
 application ──► BlobStore (API)                                   stratum-core
                   │ DefaultBlobStore: validation → capability check →
                   │ interceptor chain → driver call → error translation
                   ▼
                 BlobStoreDriver (SPI)
       ┌───────────┼──────────────┬───────────────┐
  S3BlobStoreDriver  AzureBlobStoreDriver  GcsBlobStoreDriver  InMemoryBlobStoreDriver
```

| Pattern | Where |
|---|---|
| Bridge | `BlobStore` / `DefaultBlobStore` (abstraction) ↔ `BlobStoreDriver` (implementor) |
| Plugin, Separated Interface (Fowler) | `CloudProvider` in core, discovered from provider jars via `ServiceLoader` |
| Abstract Factory | `ProviderConnection` creates the family of drivers for one account/region |
| Registry, Facade | `Stratum` holds named `CloudContext`s |
| Gateway, Adapter | Each driver wraps a native SDK client |
| Template Method | `AbstractBlobStoreDriver` (translation fallback, unwrap) |
| Chain of Responsibility | `Interceptor` / `InterceptorChain` (`RetryInterceptor` included) |
| Value Object | `BlobKey`, `ETag`, `ByteRange`, `ProviderConfig` (immutable) |
| Service Stub | `memory` provider for unit tests |
| Exception Translation | sealed `CloudException` hierarchy with `isRetryable()` |

**Beyond the lowest common denominator:** `capabilities()` tells you which optional features this
client supports, based on the provider and its credentials. `unwrap(S3Client.class)` gives you the
native client.

**Portable semantics, verified by the TCK:** lexicographic listing with opaque page tokens,
idempotent delete, create-only and compare-and-swap writes, range reads with total size, and copy
(server-side, or a download-and-upload fallback when the endpoint lacks it). On GCS the `ETag` is
the object generation, so `ifMatch` behaves the same everywhere.

**Retries:** the cloud SDKs already retry transient errors. `RetryInterceptor` is opt-in for uniform,
provider-neutral retries on idempotent operations.

## Build and test

```bash
mvn verify          # unit tests + TCK on every provider against Floci emulators (needs Docker)
mvn test            # unit tests + TCK on the in-memory provider only
```

Integration tests (`*IT`) use [Floci](https://floci.io) through Testcontainers: `floci/floci`
(AWS), `floci/floci-az` (Azure) and `floci/floci-gcp` (GCP). Image versions are pinned in the parent
POM (`floci.*.image`). They are skipped automatically when Docker is unavailable. For manual
experiments, `docker compose up -d` starts all three emulators.

Behaviour the emulators do not implement is declared per provider in `knownEnvironmentGaps()`, each
confirmed with raw HTTP requests against the emulator. Those tests are reported as skipped with the
reason, so the TCK itself stays strict for runs against real clouds:

| Emulator | Gap | Test skipped |
|---|---|---|
| Floci 2.2.0 (AWS) | drops `Content-Language` | `putStoresStandardHttpHeaders` |
| Floci-az 0.14.0 | ignores `If-None-Match` on Put Block List | `writeInPartsWithIfNotExistsRejectsExistingBlob` |
| Floci-az 0.14.0 | no server-side copy (Stratum falls back to download + upload) | n/a |
| Floci-gcp 0.10.0 | drops `cacheControl` on copy | `copyPreservesHttpHeaders` |
| Floci-az 0.14.0 | no Set Blob Tier, Set Blob Properties or Blob Batch (501); no `x-ms-access-tier` | tier and metadata-update tests (batch delete falls back to single deletes) |
| Floci-gcp 0.10.0 | metadata PATCH replaces the map and stores `null` instead of removing keys | `updateMetadataReplacesHeadersAndMetadataButNotContent` |

## Roadmap

1. ~~Core, SPI, storage on AWS/Azure/GCP, TCK, Floci tests~~
2. Cache: one Redis/Valkey driver plus cloud auth (ElastiCache IAM, Entra ID, Memorystore IAM)
3. SQL: `DataSource` factory with cloud IAM auth and token refresh (RDS IAM, Entra ID, Cloud SQL connector)
4. Search/index: OpenSearch, Azure AI Search, GCP backend to be decided
5. ~~Spring Boot starter: `stratum.*` properties, auto-configuration, health and Micrometer~~
6. Blob: archive restore, KMS encryption, versioning, tags, retention
7. JPMS module descriptors, japicmp API compatibility checks, real-cloud CI profile
