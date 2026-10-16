---
title: Errors and retries
parent: API guide
nav_order: 9
---

# Errors and retries
{: .no_toc }

Every provider translates its native SDK exceptions into one portable error model, so failures are
handled the same way on every cloud.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## The error model

All failures are unchecked subtypes of `CloudException`. The hierarchy is **sealed**: providers can't
add types, so a `switch` over it is exhaustive and stays correct as providers are added.

| Exception | Meaning | Retryable |
|---|---|---|
| `NotFoundException` | the blob, item, secret, topic or other resource doesn't exist | no |
| `AlreadyExistsException` | creating something that already exists | no |
| `PreconditionFailedException` | a condition failed: `ifMatch`, `ifNotExists`, `ifVersion`, a transaction check, or a blob that changed mid-download | no |
| `AccessDeniedException` | missing permissions, bad credentials, or an untrusted TLS certificate | no |
| `InvalidArgumentException` | the request was rejected as invalid, such as a name the backend can't store | no |
| `UnsupportedFeatureException` | a feature this provider doesn't support (see [capabilities](capabilities-and-unwrap.md)) | no |
| `DataIntegrityException` | a checksum mismatch on upload or download | no |
| `ThrottledException` | the provider is rate limiting you; `retryAfter()` says for how long, when the provider says | **yes** |
| `UnavailableException` | a transient outage, timeout, or a resource that's briefly busy, such as a secret still being deleted | **yes** |
| `UnknownCloudException` | anything not mapped above | no |

Every `CloudException` also tells you where it came from:

```java
try {
    store.get(key);
} catch (CloudException e) {
    e.isRetryable();          // true for ThrottledException and UnavailableException
    e.provider();             // Optional: "aws", "azure", "gcp", ...
    e.providerErrorCode();    // Optional: the native code, such as "NoSuchKey" or "BlobNotFound"
    e.getCause();             // the native SDK exception
}
```

## Handling errors portably

The same handler works on every cloud:

```java
try (Blob blob = store.get(key)) {
    return Optional.of(blob.readAllBytes());
} catch (NotFoundException e) {
    return Optional.empty();                       // NoSuchKey, BlobNotFound or 404, whichever cloud
}
```

```java
try {
    orders.update(key, update, WriteOptions.ifVersion(seen));
} catch (PreconditionFailedException e) {
    throw new ConflictException("Order changed since it was read");
}
```

With pattern matching over the sealed hierarchy:

```java
String describe(CloudException e) {
    return switch (e) {
        case NotFoundException nf -> "missing";
        case PreconditionFailedException pf -> "conflict";
        case ThrottledException t -> "slow down" + t.retryAfter().map(d -> " for " + d).orElse("");
        case AccessDeniedException ad -> "forbidden";
        default -> "failed: " + e.getMessage();
    };
}
```

## Retries

The cloud SDKs already retry transient errors such as network failures, 5xx responses and throttling.
On top of that, Halyx offers an opt-in, provider-neutral `RetryInterceptor`:

```java
Halyx halyx = Halyx.builder()
        .register("aws", ProviderConfig.of("aws").region("eu-west-2"))
        .interceptor(new RetryInterceptor(new RetryPolicy(
                4,                          // attempts, including the first
                Duration.ofMillis(200),     // initial backoff
                Duration.ofSeconds(5),      // maximum backoff
                2.0)))                      // multiplier
        .build();
```

- It retries **retryable** errors (`ThrottledException`, `UnavailableException`) on **idempotent**
  operations only, with exponential backoff and full jitter. `RetryPolicy.defaults()` makes 3 attempts,
  starting at 100 ms and capped at 2 seconds.
- When the provider says how long to wait (`Retry-After`, or Azure's `x-ms-retry-after-ms`), it waits at
  least that long. If that is longer than `maxBackoff`, it rethrows at once instead of retrying early.
- Writes that aren't idempotent are never retried automatically. Where retrying a write is safe, such as
  rewriting a secret that's still being deleted, the application decides.

AWS clients use the SDK's **adaptive** retry mode by default (`aws.retry-mode`: `adaptive`, `standard` or
`legacy`). It adds a client-side rate limiter that backs off while S3 returns 503 SlowDown. The limiter
applies per provider connection; choose `standard` if a throttled bucket mustn't slow calls to others.

{: .note }
Halyx doesn't hide eventual consistency or asynchronous deletes behind waits. When a backend isn't ready
yet, as with a Key Vault or Secrets Manager name that's still being deleted, the call fails fast with
the retryable `UnavailableException`, and your code, or your `RetryInterceptor`, chooses how to wait.

## Interceptors

Interceptors wrap every operation on object storage, NoSQL, messaging, caches, and parameters and
secrets (SQL goes straight through JDBC). That makes them the place for cross-cutting behaviour such as
retries, metrics, logging and auditing:

```java
class SlowCallLogger implements Interceptor {
    @Override
    public <T> T intercept(OperationContext context, Invocation<T> invocation) {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (millis > 500) {
                log.warn("{} {} on {} took {} ms", context.service(), context.operation(), context.resource(), millis);
            }
        }
    }
}

Halyx halyx = Halyx.builder()
        .register("aws", ProviderConfig.of("aws"))
        .interceptor(new SlowCallLogger())
        .interceptor(new RetryInterceptor(RetryPolicy.defaults()))
        .build();
```

`OperationContext` describes the call: `service()` (`storage`, `nosql`, `messaging`, `cache` or
`parameters`), `operation()` (such as
`put`), `provider()`, `resource()` (the bucket, table, topic and so on) and `idempotent()`. Interceptors
run in registration order, outermost first. Errors reaching an interceptor are already translated into
`CloudException`s.

In Spring Boot, every `Interceptor` bean is applied in `@Order` order, and Micrometer observations are
added for you. See [Spring Boot](../spring-boot.md#observability).
