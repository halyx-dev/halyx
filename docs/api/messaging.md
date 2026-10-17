---
title: Messaging
parent: API guide
nav_order: 3
---

# Messaging: `Publisher` and `Subscription`
{: .no_toc }

Publish to topics and consume subscriptions with one API on **Amazon SNS and SQS**, **Azure Service
Bus** and **Google Cloud Pub/Sub**, plus an in-memory broker. Delivery is **at least once** on every
provider, so make handlers idempotent.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

| | AWS | Azure | Google Cloud |
|---|---|---|---|
| `Publisher` | SNS topic (standard or FIFO), or an SQS queue with `aws.target=sqs` | Service Bus topic, or a queue with `azure.servicebus.entity=queue` | Pub/Sub topic |
| `Subscription` | SQS queue subscribed to the topic (raw delivery or SNS envelope, both handled) | Service Bus queue, or `<topic>/subscriptions/<name>` | Pub/Sub pull subscription |

Topics, subscriptions and dead-letter settings are infrastructure: create them with your usual tooling.
Halyx never creates them.

## Publishing

```java
Publisher orders = halyx.provider("aws").publisher("orders");        // SNS topic name or ARN

orders.publish("{\"id\":42}");                                         // body only
PublishResult result = orders.publish(OutgoingMessage.builder(json)
        .attribute("type", "created")
        .attribute("tenant", "acme")
        .build());
result.messageId();

orders.publishAll(List.of(OutgoingMessage.of(a), OutgoingMessage.of(b)));   // batched per provider limits
```

Bodies are text (`builder(String)`) or binary (`builder(byte[])`). Attributes are string key-value
pairs, delivered alongside the body.

### Publishing without blocking

`async()` gives the publisher's asynchronous view: `publish` and `publishAll` as `CompletableFuture`s, with the
same checks (every message is checked before any is sent), claim checks (stored through the blob store's own
asynchronous view) and errors. Batches are sent one after another, in order.

```java
events.async().publish(OutgoingMessage.of(json)).thenAccept(result -> log.info("sent {}", result.messageId()));
```

Publishes are non-blocking on SNS, SQS, Service Bus and Pub/Sub; on the memory provider the blocking call runs on
a virtual thread, or on the executor given to `async(executor)`. With Micrometer Tracing, the trace is taken from
the calling thread.

## Listening

`listen` runs a consumer that pulls messages and hands each one to your handler:

```java
Subscription billing = halyx.provider("aws").subscription("orders-billing");   // the SQS queue

MessageConsumer consumer = billing.listen(message -> {
    bill(message.bodyAsString(), message.attribute("tenant").orElse("default"));
}, ConsumerOptions.DEFAULTS.withConcurrency(32));

// on shutdown: stop pulling and let running handlers finish
consumer.close();
```

- **Returning acks** the message. **Throwing nacks** it for redelivery with exponential backoff: 1 second,
  doubling up to 5 minutes by default (`ConsumerOptions.withBackoff(initial, max)`).
- Each message runs on its own virtual thread, at most `concurrency` at once (16 by default). The consumer only asks for
  as many messages as it has free slots, so slow handlers slow the pulling instead of piling up leased
  messages.
- While a handler runs, its message's lease is extended, so long handlers don't cause redeliveries.
- Messages with the same ordering key are handled one at a time, in order.
- Acks are sent in batches (10 per request on SQS, 100 on Pub/Sub; Service Bus settles one at a time),
  flushed when a batch fills or 100 ms after its first ack. A lost batched ack only means a redelivery.
- `consumer.statistics()` reports messages received, acked, nacked and failed, messages in flight, and
  receive errors.

## Receiving by hand

`receive` pulls a batch, and you settle each message yourself:

```java
for (ReceivedMessage message : billing.receive(10, Duration.ofSeconds(20))) {
    try {
        handle(message);
        message.ack();
    } catch (Exception e) {
        message.nack(Duration.ofSeconds(30));   // redeliver after a delay, where supported
    }
}
```

`extendLease(duration)` keeps a slow message from being redelivered. `deliveryAttempt()` tells you how
many times a message has been delivered. Manual acks are sent at once.

## Ordering, deduplication and delays

```java
orders.publish(OutgoingMessage.builder(json)
        .orderingKey("customer-42")        // delivered in order per key
        .deduplicationId("order-42-v3")    // dropped if seen again within the window
        .delay(Duration.ofMinutes(5))      // delivered later
        .build());
```

These are declared [capabilities](capabilities-and-unwrap.md): asking for one the provider doesn't have
fails rather than being ignored.

| | AWS | Azure | Google Cloud |
|---|---|---|---|
| Lease | queue visibility timeout | lock duration (renewed to extend further) | ack deadline |
| Ordering keys (`ORDERING`) | FIFO topics and queues (message groups) | session-enabled entities, with `azure.servicebus.sessions=true` on the topic and subscription | message ordering enabled on the subscription |
| Deduplication ids (`DEDUPLICATION`) | FIFO topics and queues | entities with duplicate detection | no |
| Delayed delivery (`DELAYED_DELIVERY`) | SQS queues, up to 15 minutes | yes (scheduled enqueue) | no |
| `nack(delay)` (`NACK_DELAY`) | honoured | redelivers at once | honoured |
| Max body | 256 KiB | 256 KiB standard, up to 100 MB premium (`azure.servicebus.max-message-bytes`) | 10 MB |
| Dead letters | queue redrive policy | max delivery count | dead-letter policy |

On Service Bus, a session subscription holds up to `azure.servicebus.max-sessions` sessions (8) at once.
No other consumer can take a session while it's held, so each ordering key is processed in order across
instances.

## Claim checks

Bodies over a threshold go through a `BlobStore`, and the message carries only the key. Both sides use
the same `ClaimCheck`:

```java
var claims = new ClaimCheck(halyx.provider("aws").blobStore("message-bodies"), 200 * 1024, "orders/");

Publisher orders = aws.publisher(TopicConfig.builder("orders").claimCheck(claims).build());
Subscription billing = aws.subscription(SubscriptionConfig.builder("orders-billing").claimCheck(claims).build());
```

Stored bodies aren't deleted on ack, since other subscriptions may still need them. Expire them with a
bucket lifecycle rule.

## Provider notes

- **SNS and SQS:** binary bodies travel base64-encoded, and messages carry at most 10 attributes. Halyx's
  own attributes (`halyx-encoding`, `halyx-claim-check`, and trace headers) count towards that limit.
- **Pub/Sub:** a batch with mixed ordering keys is sent as one request per key, as Pub/Sub requires.
- **Service Bus:** messages published without an ordering key to a session entity each get their own
  session.

## Native clients

```java
SnsClient sns = orders.unwrap(SnsClient.class);                               // AWS publisher (SqsClient with aws.target=sqs)
SqsClient sqs = billing.unwrap(SqsClient.class);                              // AWS subscription
ServiceBusSenderClient sender = orders.unwrap(ServiceBusSenderClient.class);  // Azure publisher
ServiceBusReceiverClient receiver = billing.unwrap(ServiceBusReceiverClient.class);  // Azure subscription
TopicAdminClient topics = orders.unwrap(TopicAdminClient.class);              // Google Cloud publisher
SubscriptionAdminClient subscriptions = billing.unwrap(SubscriptionAdminClient.class);  // Google Cloud subscription
```

Session subscriptions on Service Bus unwrap to `ServiceBusSessionReceiverClient`. See
[Capabilities and native clients](capabilities-and-unwrap.md).
