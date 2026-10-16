---
title: Caches
parent: API guide
nav_order: 7
---

# Caches: `Cache<V>`
{: .no_toc }

A portable Redis and Valkey cache on every cloud: **Amazon ElastiCache** (Redis OSS and Valkey, including
Serverless), **Azure Cache for Redis** and **Azure Managed Redis**, **Google Memorystore** (Redis, Redis
Cluster and Valkey), any self-hosted Redis or Valkey server, and an in-memory cache. Only authentication
differs between clouds.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Configuring a cache

```java
Cache<String> sessions = halyx.provider("aws").cache(
        CacheConfig.builder(URI.create("rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379"))
                .namespace("sessions")                      // keys become sessions:<key>
                .defaultTtl(Duration.ofMinutes(30))
                .auth(CacheAuth.iam("sessions-app"))        // the ElastiCache IAM user id
                .property(AwsProperties.ELASTICACHE_CACHE_NAME, "sessions")
                .build());
```

| `CacheConfig` option | Meaning |
|---|---|
| endpoint | `redis://host:port`, or `rediss://` for TLS |
| `namespace(prefix)` | a key prefix, so several caches can share a server; `clear()` is limited to it |
| `defaultTtl(duration)` | the expiry of writes that don't pass one |
| `auth(CacheAuth)` | see [authentication](#authentication) |
| `cluster(true)` | cluster mode, with topology refresh |
| `failOpen(true)` | see [fail-open](#fail-open) |
| `property(name, value)` | provider and driver settings, such as `redis.command-timeout` |

Values go through a `Codec<V>`: `cache(config)` gives a `Cache<String>`, and
`cache(config, Codec.bytes())` or `cache(config, Codec.of(encode, decode))` gives any other type.

## Operations

```java
sessions.put("user-42", json);                                   // with the default TTL
sessions.put("otp-42", code, Duration.ofMinutes(5));
Optional<String> session = sessions.get("user-42");
Map<String, String> many = sessions.getAll(List.of("user-1", "user-2"));

String profile = profiles.getOrLoad("user-42", () -> repository.load(42));   // cache-aside
boolean created = sessions.putIfAbsent("lock:job", "worker-1", Duration.ofSeconds(30));
long hits = counters.increment("hits:2026-10-11", 1);
```

| Kind | Operations |
|---|---|
| Basic | `get`, `getAll`, `put` with an optional TTL, `putAll`, `delete`, `deleteAll`, `exists`, `ttl`, `expire` |
| Cache-aside | `getOrLoad`: concurrent misses in one JVM share a single load |
| Atomic | `putIfAbsent`, `update` (keeps the TTL), `replace` (compare-and-set), `getAndDelete` (one-time tokens), `deleteIfEquals` |
| Counters | `increment(key, delta)`, and `increment(key, delta, ttl)` for fixed-window rate limits (the TTL is set once, atomically) |
| Locks | `tryLock(name, lease)` and `lock(name, lease, wait)`: lease locks with a random token, so only the current holder can release or extend them |
| Maintenance | `clear()` deletes the namespace with non-blocking SCAN and UNLINK across all cluster nodes (refused without a namespace) |
| Observability | `statistics()`: hits, misses, puts, evictions, loads, load failures, fail-open skips and hit ratio |

Atomic operations run as single-key Lua scripts, so they behave the same in cluster mode.

### Rate limiting

```java
long requests = limits.increment("api:" + clientId + ":" + minute, 1, Duration.ofMinutes(1));
if (requests > 100) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
}
```

### Locks

```java
Optional<CacheLock> lock = locks.lock("nightly-report", Duration.ofMinutes(5), Duration.ofSeconds(10));
if (lock.isPresent()) {
    try (CacheLock held = lock.get()) {          // released on close
        runReport();
        held.extend(Duration.ofMinutes(5));      // keep it while still working
    }
}
```

## Authentication

| `CacheAuth` | AWS | Azure | Google Cloud |
|---|---|---|---|
| `iam()` / `iam(user)` | ElastiCache IAM: a SigV4-signed token (15 minutes); the username is the ElastiCache user id; needs `aws.elasticache.cache-name` | an Entra ID token for `https://redis.azure.com/.default`; the username is the token's `oid` | Memorystore IAM: an OAuth access token from Application Default Credentials |
| `password(...)` | AUTH token, or ACL user and password | access key | AUTH string |
| `none()` | local or unsecured servers | | |

IAM tokens are minted again before they expire, and open connections re-authenticate with them, so
long-lived connections survive token rotation.

## TLS

`rediss://` endpoints use TLS with full certificate and hostname checks.

| Property | Use it for |
|---|---|
| `redis.ca-certificate` | a PEM file of CA certificates to trust, for servers with a private CA such as Memorystore |
| `redis.verify-peer=ca` | a tunnel or proxy whose local address the certificate can't name: the chain is still checked against the trusted CA, but not the name |
| `redis.verify-peer=false` | skips certificate checks altogether, for local tests only |
| `redis.dns-resolver=jdk` | resolve host names like the rest of the JVM (the `jdk.net.hosts.file` hosts file, DNS cache TTLs, custom `InetAddressResolver`s), instead of Netty's own DNS client |

Untrusted certificates are reported as `AccessDeniedException`: a configuration error, never treated as
a miss under fail-open.

## Fail-open

With `failOpen(true)`, an unavailable server (connection failure, timeout or throttling):
- turns reads into misses
- makes `getOrLoad` call the loader
- skips plain writes

After 5 failures in a row, calls skip the server for 10 seconds (a circuit breaker), so a dead cache
doesn't add a timeout to every request. Operations whose result matters (`putIfAbsent`, `replace`,
`getAndDelete`, `deleteIfEquals`, counters and locks) still fail. Connections are opened lazily and
retried, so an application starts even while its cache is down. The default command timeout is 2
seconds (`redis.command-timeout`).

## Implementation

The shared `halyx-redis` module uses Lettuce 6.8, which stays on Netty 4.1 like the AWS and Azure SDKs.
It runs standalone or in cluster mode, with one connection per endpoint and credentials.

Not included yet: endpoint discovery by cache name (you configure the endpoint URI), stampede protection
across instances, read-from-replica, near caching, and data structures beyond strings (use `unwrap`).

## Native clients

```java
StatefulRedisConnection<byte[], byte[]> connection = sessions.unwrap(StatefulRedisConnection.class);
// cluster mode: StatefulRedisClusterConnection; the client: RedisClient or RedisClusterClient
```

See [Capabilities and native clients](capabilities-and-unwrap.md).
