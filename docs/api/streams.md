---
title: Streams
parent: API guide
nav_order: 5
---

# Streams: `StreamProducer` and `StreamConsumer`
{: .no_toc }

Send records to topics and read them in consumer groups with one API on **Amazon MSK**, **Azure Event Hubs**
(through its Kafka endpoint) and **Google Cloud Managed Service for Apache Kafka**, plus in-memory topics. All
three are Kafka: records are kept in partitioned, ordered logs, and each consumer group keeps its own position
in each partition. Delivery is **at least once**, so make handlers idempotent.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

| | AWS | Azure | Google Cloud |
|---|---|---|---|
| Cluster | MSK (provisioned or Serverless) | Event Hubs namespace, Standard tier or above | Managed Service for Apache Kafka |
| IAM login (`StreamAuth.iam()`) | MSK IAM access control | Microsoft Entra ID | Google IAM |
| Password login | SCRAM-SHA-512 (Secrets Manager) | PLAIN, `$ConnectionString` and the connection string | PLAIN, a service account and its key |

Topics and consumer group permissions are infrastructure: create them with your usual tooling. Halyx never
creates topics.

Streams or [messaging](messaging.md)? Use messaging for work queues: each message is acknowledged on its own,
and a failed one is redelivered later while others carry on. Use streams for event logs: order is kept per key,
consumers can replay from any position, and many groups read the same records independently.

## Clusters

A `StreamConfig` says where the cluster is and how clients log in. Producers and consumers on the same cluster
take the same config:

```java
StreamConfig events = StreamConfig.builder("boot-abc123.c2.kafka-serverless.eu-west-2.amazonaws.com:9098")
        .auth(StreamAuth.iam())
        .build();
```

| Auth | What it does |
|---|---|
| `StreamAuth.iam()` | Short-lived tokens from the provider's credentials, over TLS (SASL `OAUTHBEARER`). Clients fetch a new token before the current one expires. |
| `StreamAuth.password(mechanism, username, password)` | A username and password over TLS: `PLAIN`, `SCRAM_SHA_256` or `SCRAM_SHA_512`. |
| `StreamAuth.none()` | No login: a plaintext listener, or TLS with `kafka.security.protocol=SSL`. |

Cluster, producer and consumer properties that start with `kafka.` go to the Kafka clients without the
prefix, so any client setting can be tuned: `kafka.linger.ms`, `kafka.compression.type`,
`kafka.max.poll.records`. They override what Halyx and the provider set.

## Sending

```java
StreamProducer orders = halyx.provider("aws").streamProducer(StreamProducerConfig.of(events, "orders"));

SendResult result = orders.send("customer-42", json);           // key and value
result.partition();
result.offset();

orders.send(OutgoingRecord.builder(json)
        .key("customer-42")
        .header("type", "created")
        .build());

orders.sendAll(List.of(OutgoingRecord.of("customer-42", a), OutgoingRecord.of("customer-7", b)));
```

- **Keys choose the partition**, so records with the same key are read in the order they were sent. Records
  without a key are spread across partitions.
- **`send` waits until the broker has stored the record**, and `sendAll` until it has stored all of them, with
  results in the same order.
- **Values are bytes or text**, and headers are string key-value pairs. Header names starting with `halyx-`
  are reserved.
- **A producer is thread-safe.** Keep one per topic; producers on the same cluster share one Kafka producer.
- **`partitionCount()`** asks the cluster for the topic's partitions, which also checks that it exists.

## Listening

`listen` runs the consumer and hands each record to your handler:

```java
StreamConsumer billing = halyx.provider("aws").streamConsumer(StreamConsumerConfig.builder(events, "orders", "billing")
        .startPosition(StartPosition.EARLIEST)
        .build());

StreamListener listener = billing.listen(record -> {
    bill(record.key().orElseThrow(), record.valueAsString());
}, ListenerOptions.DEFAULTS.withDeadLetter(deadLetters, 5));

// on shutdown: let running handlers finish, commit, and leave the group
billing.close();
```

- **Partitions are handled in order, and in parallel.** Each partition the consumer owns runs on its own
  virtual thread, one record at a time.
- **Returning commits the record.** Handled offsets are committed every second (`withCommitInterval`), and
  always before partitions are handed to another member or the listener stops. A lost commit only means
  records are read again.
- **Throwing retries the same record** with exponential backoff, 1 second doubling up to 5 minutes by default
  (`withBackoff`). The rest of its partition waits, so the order holds; other partitions carry on. The
  consumer keeps polling throughout, so a long retry never drops it from its group.
- **Dead letters:** with `withDeadLetter(producer, maxAttempts)`, a record that fails `maxAttempts` times goes
  to that producer's topic, with `halyx-dead-letter-topic`, `-partition`, `-offset` and `-error` headers, and
  its partition moves on. Without one, a failing record is retried until it succeeds.
- **Backpressure:** each partition reads up to `maxBufferedRecords` (500) ahead of its handler, then pauses.
- `listener.statistics()` reports records received, handled, failed and dead-lettered, records in flight,
  read errors and the partitions this member owns.

## Reading by hand

`poll` returns the next records from the partitions this member owns, and `commit` records the position after
everything `poll` has returned:

```java
try (StreamConsumer audit = halyx.provider("aws").streamConsumer(StreamConsumerConfig.of(events, "orders", "audit"))) {
    while (running) {
        for (ReceivedRecord record : audit.poll(Duration.ofSeconds(1))) {
            archive(record);
        }
        audit.commit();
    }
}
```

Records polled but not committed are read again, by this member or whichever member owns the partition next.
A consumer either polls by hand or runs a listener, not both, and `poll` and `commit` belong to one thread.

## Consumer groups

- **Every group reads every record.** Consumers in the same group share the topic's partitions between them;
  start more members to read in parallel, up to one per partition.
- **Where a new group starts** is `startPosition`: `LATEST` (the default) reads only new records, `EARLIEST`
  everything the topic retains. A group with committed positions always resumes from them.
- **Members leave the group on `close()`**, and their partitions move to the others.

## Idempotence, transactions and large values

```java
StreamProducer payments = aws.streamProducer(StreamProducerConfig.builder(events, "payments")
        .transactional(true)            // sendAll is all or nothing
        .build());

StreamConsumer ledger = aws.streamConsumer(StreamConsumerConfig.builder(events, "payments", "ledger")
        .readCommitted(true)            // never see aborted records
        .build());
```

These are declared [capabilities](capabilities-and-unwrap.md): asking for one the provider doesn't have
fails rather than being ignored.

| | AWS | Azure | Google Cloud |
|---|---|---|---|
| Idempotent producers (`IDEMPOTENCE`) | yes | yes | yes |
| Transactions (`TRANSACTIONS`) | yes | no | yes |
| Max record | 1 MB by default (`kafka.max.request.size` and the topic's limit) | 1 MB | 1 MB by default |

Event Hubs offers transactions only on its premium and dedicated tiers, as a preview, so Halyx doesn't declare
them on Azure. Idempotent producers work on Event Hubs, Standard tier included, and are on by default there, as
on the other clouds.

Values over a size threshold can go through a blob store with the same `ClaimCheck` as messaging, on both the
producer and the consumer config. Without one, a value over the limit fails with `InvalidArgumentException`.

## Provider notes

- **MSK:** IAM clients use port 9098 (9198 from outside the VPC with public access). Tokens are signed for the
  connection's region; set `aws.msk.region` on the cluster if it differs. The identity needs
  `kafka-cluster:Connect` plus the topic and group actions it uses.
- **Event Hubs:** bootstrap `<namespace>.servicebus.windows.net:9093`. Tokens are for the namespace
  (`azure.event-hubs.scope` overrides it), and the identity needs the Azure Event Hubs Data Sender or Receiver
  role. Halyx applies Microsoft's recommended client settings, such as `connections.max.idle.ms=180000`, since
  Event Hubs closes idle connections after 240 seconds.
- **Managed Kafka:** the identity needs the Managed Kafka Client role. Service accounts are detected; for a
  gcloud user login, set `gcp.kafka.principal` to the account's email.

## Native clients

```java
Producer<byte[], byte[]> producer = orders.unwrap(Producer.class);    // org.apache.kafka.clients.producer
Consumer<byte[], byte[]> consumer = billing.unwrap(Consumer.class);   // only from the thread that polls
```

The same on every provider. See [Capabilities and native clients](capabilities-and-unwrap.md).

For Kafka's own clients, or any library that takes Kafka client properties, `kafkaClientProperties` gives the
properties Halyx' producers and consumers use: bootstrap servers, TLS, and SASL with the cloud login (MSK IAM,
Entra ID or Google IAM tokens, refreshed before they expire), with the provider's defaults and the cluster's
`kafka.*` properties.

```java
Map<String, Object> login = aws.kafkaClientProperties(events);   // events: the StreamConfig
try (Admin admin = Admin.create(login)) {
    admin.createTopics(List.of(new NewTopic("orders", 6, (short) 3))).all().get();
}
```

Some values are objects rather than strings (the login's token source), so pass the map to the client as it
is. [Spring for Apache Kafka](../spring-boot.md#spring-for-apache-kafka) uses the same properties.
