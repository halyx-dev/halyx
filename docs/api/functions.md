---
title: Functions
parent: API guide
nav_order: 6
---

# Functions: `FunctionInvoker`
{: .no_toc }

Invoke serverless functions on **AWS Lambda**, **Azure Functions** and **Cloud Run functions** by name, with a
payload and a response, plus in-memory functions for tests. Halyx handles each cloud's login (IAM, Entra ID tokens or
function keys, Google ID tokens) and maps each cloud's failures to the same exceptions, so the code that calls a
function doesn't change between clouds.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

| | AWS | Azure | Google Cloud |
|---|---|---|---|
| Function | a Lambda function, by name or ARN | an HTTP-triggered function in a function app | an HTTP function on Cloud Run functions |
| Call | Lambda's `Invoke` | a `POST` to the function's URL | a `POST` to the function's URL |
| Login | the provider's IAM credentials; needs `lambda:InvokeFunction` | an Entra ID token for an audience, or a function key | a Google ID token for the function's URL; needs `roles/run.invoker` |
| Headers | in the invocation's `ClientContext` | HTTP request headers | HTTP request headers |
| `invoke` payload | 6 MB | 100 MB | 32 MiB |
| Fire-and-forget `trigger` | yes, up to 1 MB, without headers | no | no |

Functions are deployed with your usual tooling; Halyx invokes them. It doesn't deploy functions, discover their URLs
through the clouds' APIs, or give you a model for writing them.

## Invoking

```java
FunctionInvoker resize = halyx.provider("images").functionInvoker("resize-image");

FunctionResponse response = resize.invoke(FunctionRequest.builder("{\"key\":\"a.png\"}")
        .header("X-Tenant", tenant)            // per call, on top of the configured headers
        .build());
byte[] out = response.payload();
```

`FunctionRequest.of(String)` and `of(byte[])` make a request with no headers; the builder adds `contentType`
(`application/json` by default), `header(name, value)` and `headers(map)`. `withPayload(newPayload, extraHeaders)`
copies a request with another payload and more headers, for interceptors and decorators.

A `FunctionResponse` has the `payload()` (and `payloadAsString()`), its `contentType()`, the response's `headers()`
(empty on Lambda) and, where the cloud reports it, the `executedVersion()` that ran (Lambda).

Asking a context again with an equal configuration returns the same invoker. Invocations go through the provider's
[interceptors](errors.md) under the service name `functions`, as the operations `invoke` and `trigger`.

## Configuration

`FunctionConfig` names the function and sets how it's called:

```java
FunctionInvoker report = halyx.provider("aws").functionInvoker(FunctionConfig.builder("monthly-report")
        .timeout(Duration.ofMinutes(5))        // 60 seconds by default
        .header("X-App", "orders")             // sent on every invocation
        .idempotent(true)                      // false by default; see Retries
        .property(AwsProperties.LAMBDA_QUALIFIER, "live")
        .build());
```

- **`timeout`**: how long an invocation may take, from sending the request to reading the response; 60 seconds by
  default. A call that takes longer throws `UnavailableException`. The function itself may still be running.
- **`headers`**: sent on every invocation. A request's own headers are added to them and replace any with the same
  name. Halyx adds no headers of its own except the login.
- **`idempotent`**: whether running the function twice for one call is harmless; false by default.

| Provider | Function name | Properties |
|---|---|---|
| AWS | the function name or ARN | `aws.lambda.qualifier`: a version or alias; the unpublished version (`$LATEST`) if unset |
| Azure | the function's name | `azure.functions.endpoint`: the function app's URL, such as `https://orders.azurewebsites.net`; `azure.functions.audience` (an Entra ID app, such as `api://orders-functions`) or `azure.functions.key` (a function key); optional `azure.functions.route`: the function's path on the app, if it isn't `api/<name>` |
| Google Cloud | the function's name | `gcp.functions.region`, for the default URL `https://<region>-<project>.cloudfunctions.net/<name>`; or `gcp.functions.url`, such as a `run.app` URL; optional `gcp.functions.audience`: the ID token's audience, if it isn't the URL |
| memory | a handler registered with `InMemoryFunctions` | |

On Azure, an audience is for a function app protected by App Service Authentication with that Entra ID app: the
provider's Entra ID credential (the default chain or a service principal) gets tokens for `<audience>/.default`. A
function key is sent as `x-functions-key`; it can come from a [parameter store](parameters.md) in Spring Boot, as
`${...}` in the property. An anonymous provider, such as one for a local Functions host, may set neither.

On Google Cloud, the ID tokens come from the provider's Google identity: Application Default Credentials, or a
service account they impersonate.

## Errors

A call either returns the function's response or throws a [`CloudException`](errors.md), translated by the cloud's
usual error mapping. There's no separate "failed response" type.

| What happened | Exception | `providerErrorCode()` |
|---|---|---|
| The function threw | `UnavailableException` | Lambda: the `errorType` of the error payload; Azure and Google Cloud: `500` |
| The function or its front end answered 400 or 416 | `InvalidArgumentException` | the status |
| 401 or 403 | `AccessDeniedException` | the status |
| 404, or no such Lambda function | `NotFoundException` | the status, or Lambda's code |
| 409 / 412 | `AlreadyExistsException` / `PreconditionFailedException` | the status |
| 429 (and 503 on Azure), or Lambda throttling | `ThrottledException`, with `Retry-After` where sent | the status, or Lambda's code |
| 501 | `UnsupportedFeatureException` | the status |
| Another 5xx, or a call that outlasted the timeout | `UnavailableException` | the status, if any |
| Any other status | `UnknownCloudException` | the status |

```java
try {
    resize.invoke(request);
} catch (NotFoundException e) {                // same branch on Lambda, Azure Functions and Cloud Run
    ...
} catch (CloudException e) {
    log.warn("resize failed: {} {}", e.providerErrorCode().orElse(""), e.getMessage());
}
```

A Lambda function that throws comes back as a 200 response with `X-Amz-Function-Error`; it's raised as the
`UnavailableException` that the Azure Functions host and the Functions Framework give with their 500, with the error
payload's `errorMessage` as the message. The message of an HTTP function's error includes the start of the response
body. Whether a status comes from the function's code or the front end doesn't matter.

Requests over the provider's limits throw `InvalidArgumentException` before anything is sent: the payload (see the
table above), and on Lambda the headers, which may be 3,583 bytes once encoded in the `ClientContext`.

## Retries

An invocation runs the function, so it isn't repeated unless the function's configuration says `idempotent(true)`.

- **Halyx' retry interceptor** retries only idempotent functions, as for every other operation.
- **The clouds' SDKs** retry a non-idempotent function only when it can't have run: after throttling (429), or a
  failure to connect. A timeout, a 5xx or a connection lost mid-call isn't retried. Idempotent functions get the
  connection's retries: `aws.retry-mode` on AWS, and the usual 5xx and I/O retries on Azure and Google Cloud.

`UnavailableException` reports itself as retryable, but a function that keeps throwing usually isn't failing for
a transient reason. With `idempotent(true)` and a retry interceptor, a failing function is run again: use them for
functions where that's what you want.

## Triggering

Lambda can start a function without waiting for it (`InvocationType=Event`). `trigger` returns once Lambda has
accepted the invocation; what the function returns or throws isn't reported.

```java
if (resize.capabilities().supports(FunctionCapability.TRIGGER)) {
    resize.trigger(FunctionRequest.of(json));
}
```

| Capability | Meaning | AWS | Azure | Google Cloud | memory |
|---|---|---|---|---|---|
| `TRIGGER` | `trigger` | yes | | | yes |
| `TRIGGER_HEADERS` | headers on a `trigger` | | | | yes |

Without `TRIGGER`, `trigger` throws `UnsupportedFeatureException`. A Lambda `ClientContext` is taken only by
synchronous invocations, so on Lambda a `trigger` with headers, configured or per request, throws
`UnsupportedFeatureException` too. Portable code starts functions with [events](events.md) or
[messaging](messaging.md) instead.

## Without blocking

`async()` gives the invoker's asynchronous view: `invoke` and `trigger` as `CompletableFuture`s, with the same
checks, interceptors and errors.

```java
resize.async().invoke(FunctionRequest.of(json)).thenAccept(r -> store(r.payload()));
```

They're non-blocking on Lambda and Azure Functions. On Google Cloud, invocations go through the connection's
blocking HTTP transport, on virtual threads. In [halyx-reactor](reactive.md), `ReactiveFunctionInvoker` gives
`Mono<FunctionResponse>` and `Mono<Void>` for `trigger`; Kotlin awaits them with `awaitSingle`.

## Timeouts on AWS

A Lambda function may run for up to 15 minutes. The AWS SDK can't lengthen an HTTP client's read timeout for one
request, so a function whose timeout is longer than the provider's `http.read-timeout` (60 seconds by default) gets
Lambda clients with an HTTP connection pool of their own, whose read timeout is the function's. Functions within the
read timeout share the connection's pool. See [HTTP connections](../providers.md#http-connections).

## Tracing

With Micrometer Tracing, `TracingFunctionInvoker` (from `HalyxTracing.functionInvoker`, or applied by the Spring Boot
starter) adds the current trace context to each invocation's headers: `traceparent` and `tracestate`, or `b3`,
whatever the propagator writes. HTTP functions get them as request headers, and Lambda functions in the
`ClientContext`'s `custom` map, from `context.getClientContext().getCustom()`. A `trigger` carries them only where
the provider declares `TRIGGER_HEADERS`, so a triggered Lambda function starts a new trace. Metrics come from the
`ObservationInterceptor`, as for other operations. See
[Observations and tracing](errors.md#observations-and-tracing).

## Testing with the memory provider

The memory provider invokes handlers you register, each call on a virtual thread of its own within the timeout:

```java
FunctionInvoker resize = halyx.provider("memory").functionInvoker("resize-image");
resize.unwrap(InMemoryFunctions.class).register("resize-image", request -> FunctionResponse.of(resized(request)));
```

A function with no handler throws `NotFoundException`, and a handler that throws raises `UnavailableException`, as
on the clouds. A handler that throws a `CloudException`, such as `InvalidArgumentException`, has it passed through,
standing in for a status the function returns. Its limits are Lambda's, the tightest of the three, and it declares
`TRIGGER` and `TRIGGER_HEADERS`.

## In Spring Boot

Functions become `FunctionInvoker` beans, and with halyx-reactor `ReactiveFunctionInvoker` views. See
[Spring Boot](../spring-boot.md#functions).
