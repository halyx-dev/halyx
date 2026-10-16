---
title: Events
parent: API guide
nav_order: 4
---

# Events: `CloudEvent` and `EventPublisher`
{: .no_toc }

Publish [CloudEvents](https://cloudevents.io) to each cloud's event router: **Amazon EventBridge**, **Azure Event
Grid** namespaces and **Google Cloud Eventarc Advanced**, plus an in-memory bus. Routers deliver events to queues
by rules you define, and you receive them with an ordinary [subscription](messaging.md), decoding each message
with `CloudEvents.decode`. Delivery is **at least once**, so deduplicate by `source` and `id`.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

| | AWS | Azure | Google Cloud |
|---|---|---|---|
| Bus | EventBridge event bus | Event Grid namespace topic | Eventarc Advanced message bus |
| Routing | rules (event patterns) | event subscriptions (filters) | enrollments (CEL) and pipelines |
| Receiving | a rule targets an SQS queue | a pull (queue) event subscription | a pipeline publishes to a Pub/Sub topic |
| Events per request | 10 | 100 | 1 |
| Largest event | about 256 KB | about 1 MB | about 1 MB |

Buses, rules, subscriptions, enrollments and pipelines are infrastructure: create them with your usual tooling.

On Google Cloud, give every event data: Pub/Sub refuses an Eventarc pipeline's delivery of an event without data,
so the event is accepted by the bus but never reaches the topic. A pipeline also publishes to Pub/Sub only with an
OAuth token: name a service account that can publish to the topic in the destination's authentication, and give
the Eventarc service agent `roles/iam.serviceAccountTokenCreator` on it.

Events or [messaging](messaging.md)? Messaging sends work to a queue you know. Events announce what happened;
the router decides who hears it, and new consumers subscribe without the publisher changing. Events from the
cloud itself, such as EC2 state changes, arrive through the same rules.

## Events

```java
CloudEvent placed = CloudEvent.builder("com.acme.order.placed", "/orders")   // type, source
        .subject("o-42")
        .json("{\"total\":12.5}")             // or data(contentType, bytes or text)
        .extension("tenant", "acme")          // 1-20 lower-case letters and digits
        .build();
```

`id` defaults to a random UUID and `time` to now. Events compare by value. Data is JSON, text or bytes with its
media type, or absent. `CloudEvents.toJson` and `CloudEvents.fromJson` convert to and from the CloudEvents JSON
format.

## Publishing

```java
EventPublisher orders = halyx.provider("orders").eventPublisher("orders");   // the bus

orders.publish(placed);
orders.publishAll(List.of(placed, shipped));   // in as few requests as the router allows
```

Events larger than the router's limit are refused with `InvalidArgumentException` before anything is sent.
`publishAll` isn't atomic: when a request fails, earlier requests' events stay published. Retrying is safe,
since each event keeps its id. Publishing goes through the provider's [interceptors](errors.md) under the
service name `events`, so retries and metrics apply as they do to other services.

| Provider | Bus name | Properties |
|---|---|---|
| AWS | the event bus name or ARN | |
| Azure | the namespace topic | `azure.eventgrid.endpoint` (the namespace's `https://` endpoint), optional `azure.eventgrid.access-key` (Entra ID otherwise) |
| Google Cloud | `projects/<p>/locations/<l>/messageBuses/<id>`, or the id with `gcp.eventarc.location` | |
| memory | an in-memory topic of the same name | |

```java
EventPublisher grid = azure.eventPublisher(EventBusConfig.builder("orders")
        .property(AzureProperties.EVENT_GRID_ENDPOINT, "https://acme.australiaeast-1.eventgrid.azure.net")
        .build());
```

On EventBridge the event's `source` and `type` become the entry's `Source` and `DetailType`, so rules can match
them, and the whole CloudEvent travels as `detail`.

## Receiving

Receive from the queue the router delivers to, and decode each message:

```java
Subscription inbox = halyx.provider("orders").subscription("orders-inbox");   // SQS queue

try (MessageConsumer consumer = inbox.listen(message -> {
    CloudEvent event = CloudEvents.decode(message);
    // ...
    message.ack();
}, ConsumerOptions.DEFAULTS)) {
    // ...
}
```

`CloudEvents.decode` reads every form the routers deliver:

| Router | What arrives | Decoded as |
|---|---|---|
| EventBridge → SQS | an EventBridge event | the CloudEvent Halyx published (its `detail`); other events, such as AWS service events, with the envelope's `id`, `source`, `detail-type` as the type, `time`, `detail` as JSON data, and `account` and `region` extensions |
| Event Grid pull subscription | CloudEvents JSON | the event |
| Eventarc pipeline → Pub/Sub | a message with `ce-` attributes (binary mode) or CloudEvents JSON | the event |

Messages that hold none of these throw `IllegalArgumentException`. Routers may reformat JSON data (EventBridge
changes whitespace and number formatting), so compare JSON data as JSON.

An Event Grid pull subscription is a subscription with the namespace endpoint and its topic:

```java
Subscription inbox = azure.subscription(SubscriptionConfig.builder("billing").topic("orders")
        .property(AzureProperties.EVENT_GRID_ENDPOINT, "https://acme.australiaeast-1.eventgrid.azure.net")
        .property(AzureProperties.EVENT_GRID_LOCK_DURATION, "PT60S")   // as the subscription is configured
        .build());
```

It supports `ack`, `nack` with a delay, and lease extension; receive waits between 10 and 120 seconds.

## In Spring Boot

Event buses become `EventPublisher` beans, and `@HalyxListener` methods can take a `CloudEvent`. See
[Spring Boot](../spring-boot.md#events).
