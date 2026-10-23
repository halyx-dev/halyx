---
title: Kotlin
parent: API guide
nav_order: 12
---

# Using Halyx from Kotlin
{: .no_toc }

Halyx is a Java library, so Kotlin can call the blocking, asynchronous and Reactor APIs today.
Coroutine support is planned separately (see the [roadmap](../roadmap.md#coming-next)). Until then,
call the Reactor API from coroutines with `kotlinx-coroutines-reactor`'s `awaitSingle()`.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Dependencies

Import the BOM and add the modules you need, as in [Getting started](../getting-started.md#add-the-dependencies).
For the Reactor examples below you also need `kotlinx-coroutines-reactor`:

```kotlin
dependencies {
    implementation(platform("dev.halyx:halyx-bom:1.0.0"))
    implementation("dev.halyx:halyx-core")
    implementation("dev.halyx:halyx-aws")
    implementation("dev.halyx:halyx-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")
}
```

## Build `Halyx` and use a `BlobStore` and a `Publisher`

```kotlin
import dev.halyx.Halyx
import dev.halyx.ProviderConfig
import dev.halyx.api.BlobContent
import dev.halyx.api.BlobKey
import dev.halyx.api.OutgoingMessage

Halyx.builder()
    .register("orders", ProviderConfig.of("aws").region("eu-west-2"))
    .build().use { halyx ->
        val aws = halyx.provider("orders")

        val invoices = aws.blobStore("acme-invoices")
        invoices.put(
            BlobKey.of("invoices/42.pdf"),
            BlobContent.ofBytes(pdfBytes),
        )

        val events = aws.publisher("order-events")
        events.publish(
            OutgoingMessage.builder(invoiceJson)
                .attribute("type", "invoiced")
                .build()
        )
    }
```

`use` closes every client Halyx opened, the same as try-with-resources in Java.

## Spring Boot `@HalyxListener` in Kotlin

```kotlin
import dev.halyx.spring.HalyxListener
import org.springframework.stereotype.Component

@Component
class Billing {
    @HalyxListener("billing")
    fun onOrder(json: String) {
        // return = ack, throw = retry with backoff
        bill(json)
    }
}
```

Constructor injection of `@Qualifier` beans works the same as in Java; see
[Spring Boot](../spring-boot.md).

## Reactor API from Kotlin

Until coroutine support arrives, bridge `Mono`/`Flux` with `awaitSingle()` and friends:

```kotlin
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitFirst

suspend fun readInvoice(invoices: BlobStore): ByteArray {
    val blob = invoices.reactive().get(BlobKey.of("invoices/42.pdf")).awaitSingle()
    return blob.content().bytes().awaitSingle()
}

suspend fun firstEvent(events: Publisher): String =
    events.reactive().receive().awaitFirst()
```

Keep the suspending functions behind a normal service boundary so the rest of the application can
stay on the blocking API if that is simpler. See [Reactive and async code](reactive.md) for which
operations are non-blocking underneath.
