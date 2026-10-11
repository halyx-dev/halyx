```text
           .-~~~-.                   .-~~-.
   .- ~ ~-(       )_ _          .-~-(      )_
  /                    ~ -.    /             ~-.
 |                         \  |                 \
  \                       .'   \               .'
    ~- . _____________ .-~       ~-._______.-~
   ██████╗██╗██████╗ ██████╗ ██╗   ██╗███████╗
  ██╔════╝██║██╔══██╗██╔══██╗██║   ██║██╔════╝
  ██║     ██║██████╔╝██████╔╝██║   ██║███████╗
  ██║     ██║██╔══██╗██╔══██╗██║   ██║╚════██║
  ╚██████╗██║██║  ██║██║  ██║╚██████╔╝███████║
   ╚═════╝╚═╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝

       one Java API for AWS · Azure · GCP
```

# Cirrus

A cloud-agnostic Java 21 API that gives microservices one consistent way to use Cloud Native services like Storage, SQL, NoSQL,
Caches, Messaging, and Parameters and Secrets across **AWS**, **Azure** and **GCP**.

With Cirrus you can build **multi-cloud microservices and applications**. A single microservice or application can work with
services on several clouds at once, such as files on S3, a cache on Azure, events on Pub/Sub and secrets
in Vault. Each cloud is registered as a named provider and used through the same API:

- **Mix clouds in one application.** Use each cloud for what it does best, or meet data-residency and
  customer requirements, without a second SDK and a second set of error handling in your code.
- **Move between clouds by configuration.** Point a blob store, cache or topic at another provider and
  the code stays the same, which keeps migrations, failover and exit plans realistic.
- **The same behaviour everywhere.** Conditional writes, versions, retries, expiry and errors such as
  `NotFoundException` mean the same on every provider. Shared conformance kits check this for each one.
- **No lowest common denominator.** Differences are declared as capabilities rather than hidden, and
  `unwrap` gives the native client when you need a provider-specific feature.
- **Develop and test locally.** An in-memory provider for unit tests, and emulators for the rest.

### Supported services

| Service | Cirrus API | AWS | Azure | GCP | Also |
|---|---|---|---|---|---|
| [Storage](#storage) | `BlobStore` | Amazon S3 | Azure Blob Storage | Google Cloud Storage | in-memory |
| [Caches](#caches) | `Cache` | Amazon ElastiCache | Azure Cache for Redis, Azure Managed Redis | Memorystore | any Redis or Valkey server, in-memory |
| [Parameters and secrets](#parameters-and-secrets) | `ParameterStore` | SSM Parameter Store, Secrets Manager | App Configuration, Key Vault | Parameter Manager, Secret Manager | HashiCorp Vault, in-memory |
| [NoSQL document stores](#nosql-document-stores) | `DocumentStore` | Amazon DynamoDB | Azure Cosmos DB for NoSQL | Cloud Firestore | in-memory |
| [Messaging](#messaging) | `Publisher`, `Subscription` | Amazon SNS, Amazon SQS | Azure Service Bus | Google Cloud Pub/Sub | in-memory |
| [SQL databases](#sql-databases) | `Database` (a JDBC `DataSource`) | Amazon RDS, Aurora | Azure Database for PostgreSQL and MySQL, Azure SQL | Cloud SQL | any PostgreSQL, MySQL or SQL Server |

Every service has a [Spring Boot](#spring-boot) integration on Boot 3.5 and 4: beans from `cirrus.*`
properties, health checks, and Micrometer metrics and observations (for SQL, Spring Boot's own JDBC health
and Hikari metrics), with trace context carried through messages.

## Storage

| | AWS | Azure | GCP | Also |
|---|---|---|---|---|
| Blob storage | Amazon S3 | Azure Blob Storage (block blobs) | Google Cloud Storage | in-memory provider |

Below is an example of a service that stores invoices on S3 and archives them on GCS, using the same API for both:

```java
try (Cirrus cirrus = Cirrus.builder()
        .register("orders", ProviderConfig.of("aws").region("eu-west-2"))
        .register("archive", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod"))
        .build()) {

    BlobStore orders = cirrus.provider("orders").blobStore("orders-bucket");          // Amazon S3
    BlobStore archive = cirrus.provider("archive").blobStore("acme-archive");         // Google Cloud Storage
    orders.put(BlobKey.of("invoices/42.pdf"), BlobContent.ofFile(path),
            PutOptions.builder().contentType("application/pdf").ifNotExists().build());
    archive.put(BlobKey.of("2026/invoices/42.pdf"), BlobContent.ofFile(path));        // same API, other cloud

    try (Blob blob = orders.get(BlobKey.of("invoices/42.pdf"))) {
        blob.content().transferTo(out);
    } catch (NotFoundException e) {
        // same exception on S3, Azure Blob and GCS
    }
}
```
### Large and streamed uploads

`write` streams a blob of any size in parts (8 MiB by default, configurable with
`PutOptions.partSize`). Up to `PutOptions.uploadConcurrency` parts (4 by default, at most 32) upload at
once, on virtual threads. Writing blocks while every slot is busy, so memory stays at about
`(uploadConcurrency + 1) × partSize`, whatever the blob size. Part buffers come from a JVM-wide pool
(at most `cirrus.buffer-pool.max-bytes` idle, 256 MiB by default) instead of being allocated per part:

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
| AWS | S3 multipart upload, parts in parallel (parts of at least 5 MiB, up to 10,000 parts) | parallel multipart above `aws.s3.multipart-threshold` (64 MiB) |
| Azure | staged blocks in parallel + Put Block List | SDK parallel block upload, block size = part size |
| GCP | resumable upload session (sequential by protocol) | resumable upload, chunk size = part size |

The first failed part aborts the whole upload. Checksums are computed in order as you write, so
`validateChecksum()` works with parallel parts.

### Parallel downloads

```java
store.download(key, Path.of("/data/model.bin"));   // 4 concurrent 8 MiB ranges

var options = GetOptions.builder().parallelism(8).partSize(16 * 1024 * 1024).validateChecksum().build();
try (Blob blob = store.get(key, options)) { ... } // an ordinary InputStream, filled by 8 ranged reads
```

With `parallelism` above 1, Cirrus looks the blob up once, then fetches byte ranges concurrently on
virtual threads, all pinned to that version (ETag or generation). It hands them to you in order.
Memory stays at about `parallelism × partSize`, using pooled buffers. A blob of at most one part is a
single request. Changes to the blob mid-download fail with `PreconditionFailedException` instead of
mixing versions, and a failed range is retried on its own by the retry interceptor. `download` writes
to a temporary file next to the target and moves it into place, so the target only ever holds a
complete blob. It also works with `range(...)`.

### Downloads and round trips

`get` costs one request on S3 and Azure: metadata comes from the download response. On Azure, `get`
metadata carries no tier (GET doesn't return it), so use `head` for that. If the connection drops
mid-download, Azure resumes from the last byte, pinned to the same ETag. On GCS, `get` looks up
metadata first and pins the download to that generation. With `ifMatch`, the download starts
alongside the lookup instead of after it.

On S3, `setTier` is a single copy request. `updateMetadata` skips the lookup of the current storage
class when the update was built with `MetadataUpdate.from(current)` plus `ifMatch(current.etag())`,
and the class maps to exactly one tier (`HOT`, `COLD`).

### Connection pooling and timeouts

Each provider connection shares one pooled HTTP client across all its services: S3, SSM and Secrets
Manager; Blob, Key Vault and App Configuration; and GCS. Tune it with the provider properties below
(in Spring, `cirrus.providers.<name>.properties`):

| Property | Default | Meaning |
|---|---|---|
| `http.max-connections` | 200 | pooled connections, which bound concurrent requests |
| `http.connect-timeout` | 10s | TCP/TLS connect timeout |
| `http.read-timeout` | 60s | maximum wait for response data |
| `http.idle-timeout` | 60s | idle connections are closed after this |
| `http.acquire-timeout` | 10s | maximum wait for a free connection (AWS) |

Durations take `500ms`, `30s` or `5m` style values, or ISO-8601 (`PT30S`). AWS uses the Apache 5
client and Azure its default (Netty) client. GCS uses an Apache transport instead of the JDK's
`HttpURLConnection`, which keeps only 5 idle connections. Google's gRPC clients (Secret Manager,
Parameter Manager) multiplex over HTTP/2 and are not affected.

### Throttling

Throttling surfaces as `ThrottledException`. When the service says how long to wait (`Retry-After`, or
Azure's `x-ms-retry-after-ms`), `retryAfter()` carries it. `RetryInterceptor` waits at least that long
before retrying. If the wait is longer than the policy's `maxBackoff`, it rethrows at once instead of
retrying early. AWS clients use the SDK's **adaptive** retry mode by default (`aws.retry-mode`:
`adaptive`, `standard` or `legacy`). It adds a client-side rate limiter that backs off while S3 returns
503 SlowDown. The limiter is per connection, so a throttled bucket also slows other buckets on that
connection; choose `standard` if that matters more.

S3 limits request rates per key prefix: about 3,500 writes and 5,500 reads per second. To go beyond
that, spread hot keys across prefixes rather than adding one sequential prefix:

```java
// orders/2026-10-10/123.json would put a whole day's writes on one prefix
String shard = Integer.toHexString(Math.floorMod(orderId.hashCode(), 16));   // 0..f
BlobKey key = BlobKey.of("orders/" + shard + "/2026-10-10/" + orderId + ".json");
```

Azure and GCS scale per account or bucket rather than per prefix. They still ramp up gradually, so
increase the load on a new bucket over minutes, not seconds.

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

Cirrus computes only the algorithm each provider checks and stores: **CRC32C** on S3 and GCS
(hardware-accelerated in the JDK), **MD5** on Azure. The provider checks it before committing (S3:
CRC32C, including full-object CRC32C for multipart uploads; Azure: MD5 per request and per block;
GCS: a CRC32C match condition). Content is read once: a validated file larger than one part is
checksummed while its parts upload, and a smaller one is read into memory once and sent from those
bytes.
Cirrus then compares the checksums the provider reports for the stored blob. A mismatch throws
`DataIntegrityException`, and the corrupt blob is deleted if it is still the version just written.
Downloads are verified at end of stream against the strongest stored checksum (`BlobMetadata.checksums()`),
including parallel downloads.

### Conditional and batch delete

```java
store.delete(key, DeleteOptions.ifMatch(etag));            // fails if the blob changed or is gone
BatchDeleteResult result = store.deleteAll(keys);           // per-key failures, never thrown
```

`deleteAll` splits keys into each provider's batch limit: S3 `DeleteObjects` (1,000), Azure Blob Batch
(256), GCS JSON batch (100). Up to 4 batches are in flight at once, and results keep the order of
your keys. Missing keys count as deleted. If an endpoint doesn't implement the batch API, Cirrus falls
back to single deletes, 16 at a time.

`listAll(prefix)` fetches the next page in the background while you work through the current one, so
long listings wait for the network about once per page.

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

### Presigned multipart uploads

For files too big for one presigned PUT (5 GiB on S3), or to let a browser upload parts in parallel
and resume after a failure, hand out one URL per part. The bytes never pass through your service:

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
| Azure | none: blocks are staged against the blob | SAS URL for Put Block | Put Block List (ETags not needed) |
| GCP | XML API multipart upload | V4-signed UploadPart | CompleteMultipartUpload (needs part ETags) |

Browsers can only read the `ETag` response header if the bucket's CORS rules expose it (S3
`ExposeHeaders: ETag`, GCS `responseHeader: ["ETag"]`). Headers, metadata and tier from the options
apply to the finished blob. Conditional writes and checksum validation are rejected, because Cirrus
never sees the bytes. The `uploadId` is opaque: on Azure it encodes the blob's headers and metadata,
so keep it server-side or treat it as untrusted input. Abandoned uploads cost storage until cleaned
up: add a lifecycle rule that aborts incomplete multipart uploads on S3 and GCS. Azure discards
uncommitted blocks after a week. On GCS, as with presigned URLs, the credentials must be able to sign.

## Caches
A portable Redis/Valkey cache on every cloud. Only authentication differs between clouds.

| | AWS | Azure | GCP | Also |
|---|---|---|---|---|
| Cache | Amazon ElastiCache (Redis OSS and Valkey, including Serverless) | Azure Cache for Redis, Azure Managed Redis | Memorystore (Redis, Redis Cluster, Valkey) | any Redis or Valkey server; in-memory provider |

```java
Cache<String> sessions = cirrus.provider("orders").cache(
        CacheConfig.builder(URI.create("rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379"))
                .namespace("sessions")                      // keys become sessions:<key>
                .defaultTtl(Duration.ofMinutes(30))
                .auth(CacheAuth.iam("sessions-app"))        // ElastiCache IAM user id
                .property(AwsProperties.ELASTICACHE_CACHE_NAME, "sessions")
                .build());

sessions.put("user-42", json);
User user = users.getOrLoad("user-42", () -> repository.load(42));   // one load per key at a time
long hits = sessions.increment("hits", 1);
```

Operations:

| Kind | Operations |
|---|---|
| Basic | `get`/`getAll`, `put` with optional TTL, `putAll`, `delete`/`deleteAll`, `exists`, `ttl`/`expire` |
| Cache-aside | `getOrLoad`: concurrent misses in one JVM share a single load |
| Atomic | `putIfAbsent`, `update` (keeps TTL), `replace` (compare-and-set), `getAndDelete` (one-time tokens), `deleteIfEquals` |
| Counters | `increment(key, delta)`, `increment(key, delta, ttl)` (fixed-window rate limits: the TTL is set once, atomically) |
| Locks | `tryLock(name, lease)` / `lock(name, lease, wait)`: lease locks with a random token, so release and extend only work for the current holder |
| Maintenance | `clear()` deletes the namespace with non-blocking SCAN + UNLINK across all cluster nodes (refused without a namespace) |
| Observability | `statistics()`: hits, misses, puts, evictions, loads, load failures, fail-open skips |

Values go through a `Codec<V>`: `Codec.string()`, `Codec.bytes()` or `Codec.of(...)`. Atomic operations
run as single-key Lua scripts, so they behave the same in cluster mode.

**Fail-open.** With `failOpen(true)`, an unavailable server (connection failure, timeout, throttling)
turns reads into misses, makes `getOrLoad` call the loader, and skips plain writes. After 5 failures
in a row, calls skip the server for 10 s (circuit breaker), so a dead cache doesn't add a timeout to
every request. Operations whose result matters (putIfAbsent, replace, getAndDelete, deleteIfEquals,
counters, locks) still fail. Connections are opened lazily and retried, so an application starts
even while its cache is down. The default command timeout is 2 s (`redis.command-timeout`).

**TLS.** `rediss://` endpoints use TLS with hostname verification. For servers with a private CA,
such as Memorystore in-transit encryption, set `redis.ca-certificate` to a PEM file. Untrusted
certificates are reported as `AccessDeniedException` (a configuration error, never treated as a
miss under fail-open).

| `CacheAuth` | AWS | Azure | GCP |
|---|---|---|---|
| `iam()` | ElastiCache IAM: SigV4-signed `connect` token (15 min); username = ElastiCache user id; needs `aws.elasticache.cache-name` | Entra ID token for `https://redis.azure.com/.default`; username = the token's `oid` | Memorystore IAM: OAuth access token from Application Default Credentials |
| `password(...)` | AUTH token, or ACL user + password | access key | AUTH string |
| `none()` | local / unsecured servers | | |

The shared `cirrus-redis` module (Lettuce 6.8, which stays on Netty 4.1 like the AWS and Azure SDKs)
runs standalone or cluster mode (`cluster(true)`, with topology refresh), TLS via `rediss://`, and one
connection per endpoint and auth. IAM tokens are minted again before they expire, and open
connections re-authenticate with them, so long-lived connections survive token rotation.

Not included yet: endpoint discovery by cache name (you configure the endpoint URI), stampede
protection across instances (TTL jitter, early refresh), negative-result caching outside Spring,
read-from-replica, near caching, and data structures beyond strings (use `unwrap()`).

## Parameters and secrets

| `ParameterKind` | AWS | Azure | GCP | Also |
|---|---|---|---|---|
| `PARAMETERS` | AWS Systems Manager Parameter Store (`SecureString` with `aws.ssm.secure=true`) | Azure App Configuration (optional label; no reads by version) | Google Cloud Parameter Manager (location `global` or a region; `gcp.parametermanager.render=true` resolves Secret Manager references) | HashiCorp Vault KV v2; in-memory provider |
| `SECRETS` | AWS Secrets Manager | Azure Key Vault (location = vault URL) | Google Cloud Secret Manager | HashiCorp Vault KV v2; in-memory provider |

One API for configuration values and secrets on every cloud and on HashiCorp Vault:

```java
ParameterStore secrets = cirrus.provider("aws").parameterStore(
        ParameterStoreConfig.secrets().prefix("orders-svc").cacheTtl(Duration.ofMinutes(5)).build());

String password = secrets.require("db/password");          // Secrets Manager secret "orders-svc/db/password"
Map<String, Parameter> db = secrets.getAll("db");           // everything under orders-svc/db
secrets.put("api/key", newKey);                            // a new version
Optional<Integer> pool = config.get("db/pool/size", Integer::valueOf);
String rdsPassword = secrets.require("db/rds", "password");  // a field of a JSON secret

try (AutoCloseable watch = secrets.watch("db", Duration.ofMinutes(1), changes ->
        changes.updated().forEach((name, p) -> pool.rotateCredentials(p.fields())))) { ... }
```

Names are portable: segments of letters, digits and single dashes separated by `/`
(`orders/db/password`). Each backend stores them in its own naming scheme: SSM uses a leading `/`,
Key Vault `--`, Secret Manager `__`, and Vault, App Configuration and Secrets Manager use the name as
it is. `getAll` returns everything at or below a prefix, and never matches partial segments (`db`
does not match `dbx/...`). Reads can be cached per store (`cacheTtl`; `refresh()` clears the cache).
Earlier versions are readable where the backend keeps them (`get(name, version)`).

Parameter Manager has no "latest" alias and the caller names each version. Cirrus writes versions
with ids that sort by creation time, and reads take the newest enabled version by creation time,
which is also correct for versions written by other tools. A read lists versions and then fetches
one, so give Parameter Manager stores a `cacheTtl`. Deletes remove all versions, then the parameter.

**JSON secrets.** `Parameter.field("password")`, `fields()` (flattened as `db.host`, `hosts[0]`) and
`require(name, field)` read JSON object values, such as RDS/Aurora-managed secrets or multi-field Vault
secrets.

**Native names.** `ParameterStoreConfig.nativeNames(true)` uses the backend's own names as they are
(no validation, no separator translation; the prefix is a literal string prefix). Use it to read
existing values like `/app/db_password` or AWS-managed `rds!db-...` secrets.

**Changes and rotations.** `watch(prefix, interval, listener)` polls versions and reports added,
updated and removed values. One daemon thread serves all watchers. Changes also invalidate the
store's cache, and failed polls are logged and retried. `statistics()` counts cache hits, backend
reads, detected changes and watch failures.

Deletes are idempotent. On Secrets Manager they skip the recovery window. On Key Vault they are soft
deletes; writing a soft-deleted name recovers it first, so names can be reused without the purge
permission. On Vault they remove all versions.

**`cirrus-vault`** supports token auth (or `VAULT_TOKEN`), a token file (Vault Agent sink; read
again when the token is rejected), AppRole, Kubernetes, GCP (metadata-server identity token) and Azure
(managed identity) login, Vault namespaces, a private CA (`vault.ca-certificate`; untrusted
certificates are a non-retryable `AccessDeniedException`), and renews tokens before they expire (in the last fifth of their life, at most 30 s
early). It uses the JDK HTTP client and a built-in JSON codec, so it adds no dependencies that
could clash with the cloud SDKs. Secrets written by other tools without a `value` field are read as
their JSON data.

## NoSQL document stores

| | AWS | Azure | GCP | Also |
|---|---|---|---|---|
| Document store | Amazon DynamoDB | Azure Cosmos DB for NoSQL | Cloud Firestore (native mode) | in-memory provider |

Items keyed by a partition key and an optional sort key, holding a portable `Document`:

```java
DocumentStore orders = cirrus.provider("aws").documentStore(TableConfig.builder("orders").sortKey("sk").build());
RecordMapper<Order> mapper = RecordMapper.of(Order.class);

Item item = orders.put(Key.of("acme", "2026-10-11#1"), mapper.toDocument(order));
orders.update(item.key(), DocumentUpdate.builder().increment("total", 5).set("status", "PAID").build(),
        WriteOptions.ifVersion(item.version()));                     // fails if someone else wrote first
orders.queryAll(Query.builder("acme").where(SortCondition.beginsWith("2026-10")).descending().build())
        .map(i -> mapper.fromDocument(i.document()))
        .forEach(this::show);
```

- **Operations:** get, put (create or replace), atomic partial `update` (set, remove, increment), delete,
  `getAll` and `putAll` in batches, and queries within one partition by sort-key range, both directions,
  with page tokens.
- **Secondary indexes:** declare them on the table (`TableConfig.index("by-status", "status", "placedAt")`)
  and query across partitions with `queryIndex(IndexQuery.builder("by-status").equalTo("PAID").build())`,
  ordered and narrowed by the string sort attribute. Items without the attribute aren't in the index.
- **Transactions:** `transact(Transaction.builder().put(...).update(...).delete(...).check(key, version).build())`
  applies up to 100 operations on one partition atomically. Any failed condition cancels all of them with
  `PreconditionFailedException`. It returns the new versions.
- **Optimistic concurrency:** every item has a `Version`. `WriteOptions.ifVersion` and `ifNotExists` turn
  lost updates into `PreconditionFailedException`.
- **Expiry:** `WriteOptions.ttl(...)` items are never returned once expired, even before the store deletes
  them, and they don't block `ifNotExists`.
- **Values:** strings, whole numbers, doubles, decimals, booleans, binary, null, lists and nested documents.
  `RecordMapper` maps records (with enums, `Instant`, `LocalDate`, `UUID`, `Optional`, lists and maps) to
  documents and back.

| | DynamoDB | Cosmos DB (NoSQL API) | Firestore (native mode) |
|---|---|---|---|
| Table | table with string `pk` (and `sk`) keys | container partitioned on `/pk` | collection; sorted items in `<table>/<pk>/<table>/<sk>` |
| Version | `cirrus_version` attribute | ETag | update time |
| Expiry | `cirrus_expires` (epoch seconds): enable TTL on it | `ttl` (container default TTL must be on, e.g. -1) | `cirrus_expires` Timestamp: add a TTL policy |
| Query | key condition on `sk` | SQL within the partition, `ORDER BY sk` | ordered on the `cirrus_sort` field (automatic single-field index) |
| Transaction | `TransactWriteItems` | transactional batch; a `check` patches a hidden field, so it moves that item's version | read-check-write transaction |
| Index | a global secondary index of that name (eventually consistent) | a cross-partition query (default indexing) | a collection-group query on the table's name: add a composite index on the index fields |
| Decimals | exact | exact (stored as a string wrapper) | stored as doubles |
| Item size | 400 KB | 2 MB | about 1 MiB |
| Reads | strongly consistent | the account's consistency level | strongly consistent |

Tables, containers and collections are infrastructure: create them with your usual tooling, with key
attributes named as in the `TableConfig` (`pk` and `sk` by default). `unwrap(DynamoDbClient.class)`,
`unwrap(CosmosContainer.class)` and `unwrap(Firestore.class)` give the native client for anything the
portable API doesn't cover, such as secondary indexes and transactions.

## Messaging

| | AWS | Azure | GCP | Also |
|---|---|---|---|---|
| Topic (`Publisher`) | Amazon SNS topic (standard or FIFO), or an Amazon SQS queue with `aws.target=sqs` | Azure Service Bus topic, or a queue with `azure.servicebus.entity=queue` | Google Cloud Pub/Sub topic | in-memory broker |
| Subscription (`Subscription`) | Amazon SQS queue subscribed to the topic (raw delivery or SNS envelope, both handled) | Service Bus queue, or `<topic>/subscriptions/<name>` | Pub/Sub pull subscription | in-memory broker |

Publish to topics and consume subscriptions with one API. Delivery is **at least once** on every provider,
so make handlers idempotent.

```java
Publisher orders = cirrus.provider("aws").publisher("orders");            // SNS topic (name or ARN)
orders.publish(OutgoingMessage.builder(json).attribute("type", "created").build());

Subscription queue = cirrus.provider("aws").subscription("orders-billing"); // the SQS queue it feeds
MessageConsumer consumer = queue.listen(message -> bill(message.bodyAsString()),
        ConsumerOptions.DEFAULTS.withConcurrency(32));
```

`listen` pulls on a virtual thread and handles each message on its own virtual thread, at most `concurrency`
at once. It only asks for as many messages as it has free slots, so slow handlers slow the pulling instead of
piling up leased messages. While a handler runs, its lease is extended. Returning acks the message; throwing
nacks it for redelivery with exponential backoff (1s doubling up to 5 minutes by default). Messages with the
same ordering key are handled one at a time, in order. `close()` stops pulling and lets running handlers
finish. Listener acks are sent in batches (10 per request on SQS, 100 on Pub/Sub; Service Bus settles one
at a time), flushed when a batch fills or 100 ms after its first ack. A lost batched ack only means a
redelivery. Use `receive(max, wait)` with `ack()`, `nack(delay)` and `extendLease(d)` to pull by hand;
those acks are sent at once.

| | AWS | Azure | GCP |
|---|---|---|---|
| Lease | queue visibility timeout | lock duration (renewed repeatedly to extend further) | ack deadline |
| Ordering keys | FIFO topics and queues (message groups) | session-enabled entities, with `azure.servicebus.sessions=true` on the topic and subscription | with message ordering enabled on the subscription |
| Deduplication ids | FIFO topics and queues | entities with duplicate detection | no |
| Delayed delivery | SQS queues, up to 15 minutes | yes (scheduled enqueue) | no |
| `nack(delay)` | honoured | redelivers at once | honoured |
| Max body | 256 KiB | 256 KiB standard, up to 100 MB premium (`azure.servicebus.max-message-bytes`) | 10 MB |
| Dead letters | queue redrive policy | max delivery count | dead-letter policy |

On Service Bus, a session subscription holds up to `azure.servicebus.max-sessions` sessions (8) at once.
No other consumer can take a session while it's held, so each ordering key is processed in order across
instances. Messages published without an ordering key to a session entity each get their own session.

Capabilities are declared (`MessagingCapability.ORDERING`, `DEDUPLICATION`, `DELAYED_DELIVERY`, `NACK_DELAY`).
Asking for one that isn't there fails rather than being ignored. Topics, subscriptions and dead-letter
settings are infrastructure: create them with your usual tooling. Cirrus never creates them.

**Claim checks.** Bodies over a threshold go through a `BlobStore`, and the message carries only the key:

```java
var claims = new ClaimCheck(cirrus.provider("aws").blobStore("message-bodies"), 200 * 1024, "orders/");
Publisher orders = aws.publisher(TopicConfig.builder("orders").claimCheck(claims).build());
Subscription billing = aws.subscription(SubscriptionConfig.builder("orders-billing").claimCheck(claims).build());
```

Stored bodies aren't deleted on ack, since other subscriptions may still need them. Expire them with a
bucket lifecycle rule. On SNS and SQS, binary bodies travel base64-encoded, and messages carry at most 10
attributes. Cirrus' own (`cirrus-encoding`, `cirrus-claim-check`, trace headers) count towards that limit.

## SQL databases

| | AWS | Azure | GCP |
|---|---|---|---|
| Database | Amazon RDS and Aurora (PostgreSQL, MySQL; SQL Server with passwords) | Azure Database for PostgreSQL and for MySQL (flexible server), Azure SQL Database | Cloud SQL (PostgreSQL, MySQL; SQL Server with passwords) |
| Passwordless login | RDS IAM authentication | Microsoft Entra ID | Cloud SQL IAM database authentication, or the Cloud SQL Java connector |

A pooled `DataSource` (HikariCP) whose connections log in with the cloud's identity instead of a stored
password. Tokens are fetched when a new physical connection opens, and cached until shortly before
they expire. Established connections stay logged in, so token lifetimes never interrupt queries.

```java
Database orders = cirrus.provider("aws").database(SqlConfig.builder(SqlEngine.POSTGRESQL)
        .host("orders.cluster-abc.eu-west-1.rds.amazonaws.com")
        .database("orders")
        .auth(SqlAuth.iam("orders_app"))
        .pool(new PoolSettings(20, 20, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30)))
        .build());
DataSource dataSource = orders.dataSource();   // to JDBC, JPA, jOOQ, Flyway
```

| Provider | `SqlAuth.iam(...)` | Engines | The username is |
|---|---|---|---|
| AWS | RDS IAM token (SigV4, signed locally, 15 min) | PostgreSQL, MySQL | the database user granted `rds_iam` |
| Azure | Entra ID access token | PostgreSQL, MySQL, Azure SQL | the identity's database role; not used for Azure SQL (`accessToken` property) |
| GCP | OAuth token, `sqlservice.login` scope | PostgreSQL, MySQL | derived from the service account if omitted (`app@project.iam` / `app`) |

RDS Proxy works as is: use the proxy endpoint as `host`, with `SqlAuth.iam(...)` when the proxy requires
IAM authentication. IAM logins turn on TLS (`sslmode=require`, `sslMode=REQUIRED` or `encrypt=true`); override it with
`jdbcProperty(...)`, for example to `verify-full` with the cloud's CA bundle. On GCP, set
`property("gcp.sql.instance", "project:region:instance")` to go through the Cloud SQL Java connector
instead of a host. The connector handles TLS and IAM itself and needs no authorized networks. Add
`com.google.cloud.sql:postgres-socket-factory` or `mysql-socket-factory-connector-j-8` to use it.

Passwords work on every provider. `SqlAuth.password(user, supplier)` asks the supplier for every new
connection, so a rotated secret takes effect as connections are replaced (`maxLifetime`, 30 minutes by
default) without a restart:

```java
SqlAuth.password("app", () -> secrets.require("orders/db", "password"))   // a cached ParameterStore
```

The JDBC driver is your dependency (`org.postgresql:postgresql`, `com.mysql:mysql-connector-j` or
`com.microsoft.sqlserver:mssql-jdbc`). The pool starts on first use, so the application can start
while the database is unreachable. The pool closes with the Cirrus context. In Spring, every
`cirrus.databases.<name>` entry becomes a `DataSource` bean, covered by Spring Boot's JDBC health check
and Hikari metrics. Cirrus configures itself before Boot's `DataSourceAutoConfiguration`, which then
backs off. Spring Data JPA and JDBC repositories, `JdbcTemplate`, Flyway and Liquibase all use the
primary Cirrus `DataSource` unchanged. With several databases, wire the extra `EntityManagerFactory`
yourself, as in plain Spring Boot. Pool settings live under `cirrus.databases.<name>.pool`, not
`spring.datasource.*`. Reactive repositories (R2DBC) aren't covered yet.

## Spring Boot

Add the starter plus the provider modules you use:

```xml
<dependency>
  <groupId>org.kamranzafar</groupId>
  <artifactId>cirrus-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.kamranzafar</groupId>
  <artifactId>cirrus-aws</artifactId>
</dependency>
```

```yaml
cirrus:
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
  caches:
    sessions:                      # Cache<String> bean name, also the default key namespace
      endpoint: rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379
      default-ttl: 30m
      auth: { type: iam, username: sessions-app }      # none | password | iam
      properties: { aws.elasticache.cache-name: sessions }
  cache-manager:                   # Spring CacheManager for @Cacheable (with @EnableCaching)
    endpoint: rediss://master.app.abc123.euw2.cache.amazonaws.com:6379
    auth: { type: password, password: ${CACHE_AUTH_TOKEN} }
    default-ttl: 10m
    ttl: { users: 1h }             # per-cache override
    namespace-prefix: "orders-svc:"
    fail-open: true                # cached methods run uncached while the cache is down
    codec: json                    # jdk (default) | json (Jackson 3 on Boot 4, Jackson 2 on Boot 3)
    trusted-packages: com.example.orders
  parameter-stores:
    app-secrets:                   # ParameterStore bean name, and the name used by cirrus: imports
      kind: secrets                # secrets | parameters
      prefix: orders-svc
      cache-ttl: 5m
      reload-interval: 1m          # for cirrus: imports; omit for read-once at startup
      # native-names: true         # use existing backend names as they are
      # expand-json: true          # (default) JSON values also become db.creds.password etc.
      # startup-attempts: 3        # (default) retries when the backend is briefly unavailable
  document-stores:
    orders:                        # DocumentStore bean name
      table: orders-v2             # DynamoDB table / Cosmos container / Firestore collection; defaults to the key
      sort-key: sk                 # omit for tables keyed by partition key only
      indexes:
        by-status: { partition-attribute: status, sort-attribute: placedAt }
  topics:
    order-events:                  # Publisher bean name
      name: orders                 # SNS topic / Service Bus topic / Pub/Sub topic; defaults to the key
      claim-check: { blob-store: invoices, threshold: 204800 }
  subscriptions:
    billing:                       # Subscription bean name, used by @CirrusListener("billing")
      name: orders-billing         # SQS queue / <topic>/subscriptions/<name> / Pub/Sub subscription
      listener: { concurrency: 32, max-backoff: 2m }
  databases:
    orders:                        # DataSource bean name; a single database is the primary DataSource
      engine: postgresql           # postgresql | mysql | sqlserver
      host: orders.cluster-abc.eu-west-1.rds.amazonaws.com
      database: orders
      auth: { type: iam, username: orders_app }    # RDS IAM / Entra ID / Cloud SQL IAM tokens
      # auth: { type: password, username: app, secret: { store: app-secrets, name: orders/db, field: password } }
      pool: { max-size: 20 }
  retry:
    enabled: false                 # the SDKs already retry; enable for uniform retries
```

```java
@Service
class InvoiceService {
    InvoiceService(@Qualifier("invoices") BlobStore invoices) { ... }
}

@Component
class Billing {
    @CirrusListener("billing")          // or (ReceivedMessage message), or (byte[] body)
    void onOrder(String json) { ... }   // return = ack, throw = retry with backoff
}
```

`cirrus.document-stores.<name>` entries become `DocumentStore` beans with a health entry each.

Listeners start once the application is ready and stop first on shutdown, letting running handlers finish.
Each consumer reports `cirrus.messaging.messages{subscription, listener, result}`,
`cirrus.messaging.in.flight` and `cirrus.messaging.receive.errors`, and appears under the `messaging` health
entry. With Micrometer Tracing, publishers add the current trace context to message attributes, and each
listener invocation runs in a consumer span that continues the trace. Bean names are shared across
`blob-stores`, `caches`, `topics`, `subscriptions` and your own beans, so keep them distinct.

What the starter provides:

| Feature | Details |
|---|---|
| `Cirrus` bean | Built from `cirrus.providers`, closed with the context. Define your own `Cirrus` bean to replace it. |
| `BlobStore` beans | One per `cirrus.blob-stores` entry, named after the entry. |
| `Cache<String>` beans | One per `cirrus.caches` entry, named after the entry. |
| `ParameterStore` beans | One per `cirrus.parameter-stores` entry, named after the entry. |
| Config import | `spring.config.import=cirrus:app-secrets` (or `cirrus:app-secrets/db`, `optional:cirrus:...`) loads a store into the `Environment` at startup. Names become property keys with `/` replaced by `.`, so `db/password` becomes `db.password`, and JSON values add their fields (`db.creds.password`). The providers and stores must be defined in the same or an earlier config file. A briefly unavailable backend is retried with backoff (`startup-attempts`). |
| Reload | With `reload-interval`, imported properties are updated in place when values change or rotate. Cirrus publishes a `CirrusParametersChangedEvent` (store name and changed keys), and Spring Cloud's `EnvironmentChangeEvent` when Spring Cloud Context is present, so `@ConfigurationProperties` and `@RefreshScope` beans follow. Without Spring Cloud, `Environment` reads and event listeners see changes; `@Value` fields keep their startup values. |
| `CacheManager` | `cirrus.cache-manager.*` backs `@Cacheable`, `@CachePut` and `@CacheEvict` (including `allEntries` and `sync = true`): per-cache TTLs, cached nulls, JDK or JSON values. JSON stores `{"@class", "v"}` and only reads back types from `trusted-packages` (no unsafe polymorphic typing); the JDK codec applies the same allow-list through an `ObjectInputFilter`. Async/reactive `@Cacheable` is not supported. |
| Parameter metrics | `cirrus.parameters.reads{store, source=cache\|backend}`, `cirrus.parameters.changes{store}`, `cirrus.parameters.watch.failures{store}`. |
| Cache metrics | With a `MeterRegistry`: Micrometer's standard `cache.gets{result=hit\|miss}`, `cache.puts`, `cache.evictions`, plus `cache.loads{result}` and `cache.fail.open`, tagged `cache=<name>`, for `Cache` beans and `CacheManager` caches. |
| Interceptors | Every `Interceptor` bean is applied in `@Order` order. Order of application, outermost first: observations, your interceptors, retries. |
| Customisation | `CirrusBuilderCustomizer` beans can add plugins or registrations. |
| Observations | With Micrometer, every operation records a `cirrus.operation` observation (timer + span) tagged with `cirrus.service`, `cirrus.operation`, `cirrus.provider` and `error`. Disable with `cirrus.observations.enabled=false`. |
| Health | A `cirrus` health contributor with one indicator per blob store (lists one item), per cache (runs an authenticated command) and per parameter store (an authenticated read). Disable with `management.health.cirrus.enabled=false`. |
| Fail fast | Startup fails, naming the property, for an unknown provider type (with the module to add), an unknown or ambiguous `provider` reference, or incomplete static credentials. |
| IDE support | Configuration metadata is generated, so `cirrus.*` keys autocomplete. |

**Supports Spring Boot 3.5.x and 4.x** (Java 21) with the same starter: your application's Boot
parent or BOM picks the Spring versions. The auto-configuration is compiled against Boot 3.5. Health
is wired with Actuator's `org.springframework.boot.actuate.health` on Boot 3, and with the new
`org.springframework.boot.health` module on Boot 4; exactly one of the two activates. The test suite
runs on both: `cirrus-spring-boot-autoconfigure` tests on 3.5, and the unpublished
`cirrus-spring-boot4-tests` module runs the same shared tests on 4.1.

## Modules

| Module | Purpose |
|---|---|
| `cirrus-bom` | Import to align Cirrus versions |
| `cirrus-core` | Portable API, provider SPI, error model, interceptors, in-memory provider. No cloud SDKs (enforced) |
| `cirrus-tck` | Conformance test kit every provider must pass |
| `cirrus-redis` | Shared Redis/Valkey cache driver (Lettuce) used by every provider |
| `cirrus-sql` | Shared HikariCP pool with per-connection credentials (passwords, cloud IAM tokens) |
| `cirrus-aws` | AWS SDK v2: S3, DynamoDB, SNS, SQS, SSM Parameter Store, Secrets Manager, ElastiCache and RDS IAM auth |
| `cirrus-azure` | Azure SDK: Blob Storage, Cosmos DB, Service Bus, Key Vault, App Configuration, Entra ID cache and database auth |
| `cirrus-gcp` | Google Cloud: Cloud Storage, Firestore, Pub/Sub, Secret Manager, Memorystore and Cloud SQL IAM auth |
| `cirrus-vault` | HashiCorp Vault (KV v2): parameters and secrets |
| `cirrus-spring-boot-autoconfigure` | Spring Boot auto-configuration, properties, health, observations |
| `cirrus-spring-boot-starter` | The dependency Spring Boot applications add |
| `cirrus-benchmarks` | JMH benchmarks and a load-test harness for real buckets (not published) |

Add `cirrus-core` plus the provider jars you need; providers are discovered with `ServiceLoader`.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.kamranzafar</groupId>
      <artifactId>cirrus-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Design

```
 application ──► BlobStore (API)                                   cirrus-core
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
| Registry, Facade | `Cirrus` holds named `CloudContext`s |
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
| Floci-az 0.14.0 | no server-side copy (Cirrus falls back to download + upload) | n/a |
| Floci-gcp 0.10.0 | drops `cacheControl` on copy | `copyPreservesHttpHeaders` |
| Floci-az 0.14.0 | no Set Blob Tier, Set Blob Properties or Blob Batch (501); no `x-ms-access-tier` | tier and metadata-update tests (batch delete falls back to single deletes) |
| Floci-gcp 0.10.0 | metadata PATCH replaces the map and stores `null` instead of removing keys | `updateMetadataReplacesHeadersAndMetadataButNotContent` |

Cache tests:

| Provider | Server | What is verified |
|---|---|---|
| shared driver | Valkey container | full cache TCK without auth and with a password; wrong or missing password; token rotation with re-authentication of open connections and fresh tokens on reconnect |
| AWS | Floci ElastiCache (real Valkey) | full cache TCK with an AUTH token; ElastiCache IAM tokens accepted by Floci's SigV4 token validator, wrong-secret and tampered tokens rejected |
| Azure | Floci-az Azure Cache for Redis (real Valkey, provisioned over ARM) | full cache TCK with the access key |
| GCP | Valkey container (Floci has no Memorystore) | full cache TCK with an AUTH string |

| shared driver | 3-master Valkey cluster | full cache TCK in cluster mode; keys spread over all masters; cross-slot bulk operations and `clear` |
| shared driver | TLS-only Valkey with a private CA | full cache TCK over `rediss://` trusting the CA; an untrusted certificate is a non-retryable `AccessDeniedException` |
| shared driver | unreachable server | fail-open misses and loader fallback, strict operations still fail, breaker short-circuits, recovery once the server starts |

Parameter store tests (the same 22-test TCK everywhere: CRUD, versions, prefixes, caching, JSON
fields, native names, watching):

| Backend | Server | Notes |
|---|---|---|
| AWS SSM Parameter Store, Secrets Manager | Floci | plus `SecureString` writes |
| Azure Key Vault, App Configuration | Floci-az | plus Key Vault name encoding and App Configuration labels; App Configuration skips the versions test |
| GCP Secret Manager | Floci-gcp (gRPC) | plus name encoding |
| GCP Parameter Manager | an in-process fake gRPC server (no emulator exists) | global and regional; latest-version selection with foreign version ids, rendering, name encoding. This checks the driver's use of the real client, not GCP's exact behaviour |
| HashiCorp Vault | Vault dev server (plain and TLS with a private CA) | plus AppRole with token renewal, token-file rotation, wrong tokens, multi-field secrets, CA trust; GCP and Azure login requests against a fake metadata service |
| Spring `spring.config.import` | Vault dev server | on Boot 3.5 and 4.1: JSON expansion, native names, live reload with change events, prefixes, optional imports |

Document store tests (the same 23-test TCK: every value type, versions and conditions, partial updates
with concurrent increments, batches beyond provider limits, sort-key queries in both directions with
paging, secondary-index queries across partitions, transactions with rollback, keys with `/ ? # % \`,
expiry):

| Backend | Server | Notes |
|---|---|---|
| in-memory store | none | the full TCK |
| DynamoDB | Floci | the full TCK |
| Firestore | Google's Firestore emulator, and Floci-gcp | the full TCK on Google's emulator; Floci skips the three tests relying on increments, which it doesn't apply reliably (lost when concurrent, set rather than added in transactions) |
| Cosmos DB | Floci-az, over TLS with a throwaway certificate (the SDK requires HTTPS) | the full TCK |
| Spring | in-memory store | on Boot 3.5 and 4.1: `DocumentStore` beans and health |

Messaging tests (the same 15-test TCK: round trips, binary bodies, ack, nack, lease expiry and
extension, batches, listener retries and concurrency, claim checks, ordering, deduplication, delays):

| Backend | Server | Notes |
|---|---|---|
| in-memory broker | none | the full TCK, which also checks the TCK itself |
| AWS SNS → SQS, plain SQS | Floci | standard and FIFO topics and queues; SNS envelope unwrapping and base64 bodies without raw delivery; SNS skips delays |
| GCP Pub/Sub | Google's Pub/Sub emulator, and Floci-gcp | everything but deduplication and delays (not in Pub/Sub). Floci never redelivers, so its run skips the three redelivery tests, which the Google emulator covers |
| Azure Service Bus | Microsoft's Service Bus emulator with SQL Edge | the full TCK: topics with subscriptions, a queue, duplicate detection, scheduled delivery, lock renewal past the lock duration, ordering through sessions |
| Spring | in-memory broker | on Boot 3.5 and 4.1: beans from properties, `@CirrusListener` with retries, claim checks, metrics, health, trace propagation |

**On real GCP:** `GcpParameterStoreIT` runs the same TCK against Secret Manager and Parameter
Manager in a real project, using Application Default Credentials. It is skipped unless the variables
are set:

```bash
CIRRUS_GCP_PROJECT=my-project CIRRUS_GCP_LOCATION=global mvn verify -pl cirrus-gcp -Dit.test=GcpParameterStoreIT
```

Not verified locally: GCP Parameter Manager against real GCP (see above), Vault GCP/Azure login
against real Vault auth backends, Vault Kubernetes
login, Entra ID logins to Key Vault and App Configuration (the emulator accepts any token), and an IAM *login* end to end (the released Floci images cannot attach IAM users to
a cache yet), Entra ID logins (Floci-az does not emulate them), and failover of a managed cluster.

## Benchmarks

```bash
mvn -pl cirrus-benchmarks -am package -DskipTests
java -jar cirrus-benchmarks/target/benchmarks.jar                      # all JMH benchmarks
java -jar cirrus-benchmarks/target/benchmarks.jar TransferBenchmark -p latencyMillis=50
```

The JMH benchmarks measure Cirrus itself, so they need no cloud account. `TransferBenchmark` and
`BulkBenchmark` run against a simulated network: every request pays a round-trip latency, and each
connection is capped at a bandwidth. That shows what the parallel engines buy without depending on
your network. One short run on a laptop (20 ms latency, 100 MB/s per connection):

| Benchmark | Sequential | Cirrus | Speed-up |
|---|---|---|---|
| 64 MiB download, 8 MiB ranges | 1,004 ms | 310 ms (parallelism 4), 219 ms (8) | 3.2x, 4.6x |
| 64 MiB upload, 8 MiB parts | 1,125 ms | 445 ms (concurrency 4), 228 ms (8) | 2.5x, 4.9x |
| Delete 500 keys, no batch API | 10,934 ms | 705 ms (16 at a time) | 15x |
| List 5,000 keys in 5 pages, 20 µs work per key | 291 ms | 194 ms (next page prefetched) | 1.5x |
| Checksum one 8 MiB part | MD5: 660 MiB/s | CRC32C: about 16 GiB/s | about 25x |

Calling the portable API costs about a microsecond more per call than calling the driver directly
(`CoreBenchmark`): noise next to any network round trip. These runs were short, with wide error bars;
rerun them on your own hardware before relying on the figures.

Emulators don't behave like cloud storage under load, so tune against a real bucket with `LoadTest`.
Each cloud's default credential chain is used, and the test deletes its objects afterwards:

```bash
java -cp cirrus-benchmarks/target/benchmarks.jar \
  -Dprovider=aws -Dbucket=my-bench-bucket -Dregion=eu-west-1 \
  -Dconcurrency=64 -Dduration=60s -Dsize=1MiB -Dmix=put:20,get:70,head:10 \
  -Dcirrus.http.max-connections=256 \
  org.kamranzafar.cirrus.benchmarks.LoadTest
```

It prints count, ops/s, MB/s, p50/p90/p99/max latency and errors for each operation. Any `cirrus.*`
property goes to the provider (for example `-Dcirrus.aws.retry-mode=standard`). Use
`-Dparallelism=8` for parallel ranged gets, and `-Dendpoint=...` for S3-compatible stores.

## Roadmap

Done:

1. ~~Core, SPI, blob storage on AWS, Azure and GCP, conformance kits, emulator tests~~
2. ~~Caches: one Redis/Valkey driver with cloud auth (ElastiCache IAM, Entra ID, Memorystore IAM)~~
3. ~~SQL: pooled `DataSource` with cloud IAM auth and token refresh (RDS IAM, Entra ID, Cloud SQL)~~
4. ~~Parameters and secrets: SSM, Secrets Manager, App Configuration, Key Vault, Parameter Manager,
   Secret Manager, HashiCorp Vault; Spring config import with reload~~
5. ~~NoSQL document stores: DynamoDB, Cosmos DB, Firestore~~
6. ~~Spring Boot starter: properties, auto-configuration, health, Micrometer, Boot 3.5 and 4~~
7. ~~Messaging: SNS/SQS, Service Bus, Pub/Sub; listeners, claim checks, tracing~~
8. ~~Production gaps: NoSQL secondary indexes and transactions, batched acks, Service Bus sessions~~

Backlog, in order:

1. Real-cloud test profile: Terraform for throwaway resources per cloud and a Maven profile running the
   conformance kits and IAM tests against them
2. Release 0.1.0: JPMS module descriptors, japicmp API compatibility checks, CI, Maven Central
3. On demand
   - Spring Data repositories over document stores (`cirrus-spring-data`): CRUD by composite id
     (`@PartitionKey`, `@SortKey`), `@Version` optimistic locking, derived queries on the partition key, sort
     range and declared indexes, keyset scrolling (`Window`/`ScrollPosition`); no table scans, general
     `@Transactional` or reactive repositories
   - R2DBC for reactive Spring Data, with cloud IAM
   - Aurora fast failover through the AWS Advanced JDBC Wrapper
   - Nested field paths in NoSQL updates
   - Cross-instance cache stampede protection (a single-flight `getOrLoad`)
   - Blob: archive restore, KMS encryption, versioning, tags, retention

Dropped, because they work already or don't earn their weight: cache endpoint discovery through cloud
APIs; push notifications for parameter changes (polling watch and reload cover it); SSM and Secret
Manager labels; EventBridge and Event Grid; GCS parallel composite uploads, the AWS CRT client and an
async API (the parallel transfer engines cover large objects). RDS Proxy needs nothing: point `host` at
the proxy endpoint, with IAM auth if the proxy requires it.
