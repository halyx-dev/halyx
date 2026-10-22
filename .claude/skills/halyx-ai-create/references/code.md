# Code patterns

Each pattern is condensed from `halyx-samples/portable-orders`; the class named after it is the full version. Imports
are `dev.halyx.*`: `nosql.Key`, `nosql.Version`, `nosql.DocumentStore`, `nosql.DocumentUpdate`,
`spring.data.document.{DocumentRepository, DocumentTable, PartitionKey, SortKey}`, `messaging.{Publisher,
OutgoingMessage}`, `spring.messaging.HalyxListener`, `events.{EventPublisher, CloudEvent}`, `stream.StreamProducer`,
`cache.Cache`, `storage.{BlobStore, BlobKey, BlobContent, PutOptions}`, `parameters.ParameterStore`,
`error.NotFoundException`, `json.Json`.

## Records and repositories (`Order`, `OrderRepository`)

```java
@DocumentTable("orders")                                  // the halyx.document-stores key
public record Order(@PartitionKey String customerId, @SortKey String orderId, OrderStatus status,
                    Instant placedAt, List<OrderLine> lines, BigDecimal total, String currency,
                    String receipt, @org.springframework.data.annotation.Version Version version) {

    static final String STATUS = "status";                // fields that partial updates set, named as the components
    static final String RECEIPT = "receipt";
}

public interface OrderRepository extends DocumentRepository<Order, Key> {   // Key with a sort key, else String
    List<Order> findByCustomerIdOrderByOrderIdDesc(String customerId);
}
```

- Reads: `findById(Key.of(partition, sort))`; derived queries on the partition key with at most one sort-key
  condition, or on a declared index. Anything else fails at startup.
- `save` with a `Version` component is optimistic: a new record only if the id is free (`DuplicateKeyException`),
  an update only if the stored version still matches (`OptimisticLockingFailureException`).
- A field a listener sets later is a partial update through the `DocumentStore` bean, so it never overwrites a
  concurrent change to the rest of the record:

```java
store.update(Key.of(order.customerId(), order.orderId()),
        DocumentUpdate.builder().set(Order.STATUS, OrderStatus.CONFIRMED.name()).set(Order.RECEIPT, key).build());
```

- Ids that sort by time: `"%013d-%s".formatted(System.currentTimeMillis(), randomHex)`.

## The service: store, then announce (`OrderService`)

```java
@Service
public class OrderService {
    OrderService(OrderRepository orders, @Qualifier("order-placed") Publisher placed,
                 ObjectProvider<IdempotencyKeys> idempotencyKeys, List<OrderPlacedHook> hooks) { ... }

    public Order place(String customerId, List<OrderLine> lines) {
        validate(customerId, lines);                      // ids that become keys are kept plain: [A-Za-z0-9-]{1,64}
        Order order = orders.save(new Order(customerId, newOrderId(), OrderStatus.PLACED, Instant.now(), ...));
        placed.publish(OutgoingMessage.builder(OrderMessages.placed(order)).attribute("type", "order.placed").build());
        hooks.forEach(hook -> hook.placed(order));        // the optional features, each @Profile'd
        return order;
    }
}
```

Write first, then announce. A failure between the two leaves the record without its announcement; say in the code
how the service recovers (a client retry with an idempotency key, or an outbox), as the sample does.

Optional features hang off the core through a hook interface (`List<OrderPlacedHook>`, empty when none is on) or
`ObjectProvider<T>` (`getIfAvailable()`), so the core has no `@Profile` checks.

## The API (`OrderController`)

- Request and view records nested in the controller; the view hides internals such as the version.
- `201 Created` with a `Location` for a new resource; `ResponseEntity.of(optional)` for a 404 on a missing one.
- The service's own exceptions mapped with `@ExceptionHandler` returning `ProblemDetail`: invalid input to 400,
  conflicts to 409.
- Halyx's `NotFoundException` becomes `Optional.empty()` at the edge of the class that reads (`ReceiptService.read`).

## Listeners (`OrderFulfilment`)

```java
@Component
class OrderFulfilment {                                   // not "Fulfilment": bean "fulfilment" is the subscription

    @HalyxListener("fulfilment")                          // a halyx.subscriptions or halyx.stream-consumers key
    void onOrderPlaced(String body) {                     // or byte[], CloudEvent, ReceivedMessage, ReceivedRecord
        String[] key = OrderMessages.key(body);
        Order order = orders.find(key[0], key[1]).orElseThrow(() -> new IllegalStateException(
                "order " + key[1] + " is not readable yet"));      // eventually consistent reads: throw to redeliver
        if (order.status() == OrderStatus.CONFIRMED) {
            return;                                       // a redelivery: already done
        }
        ...                                               // each step safe to repeat
    }
}
```

Returning acknowledges (commits, on streams); throwing redelivers with backoff, and in time dead-letters. Every
handler must be safe to run twice: check the state first, make writes conditional or overwriting, and treat a
duplicate insert as done (`catch (DuplicateKeyException alreadyRecorded)`, in `LedgerService`).

## Events (`OrderNotifications`)

```java
events.publish(CloudEvent.builder(PLACED_TYPE, SOURCE).subject(order.orderId())
        .json(OrderMessages.placed(order)).build());       // always give an event data

@HalyxListener("notifications")
void onEvent(CloudEvent event) {
    if (!event.type().equals(PLACED_TYPE) || !event.source().equals(SOURCE)) {
        return;                                            // the cloud's rule filters; the memory bus doesn't
    }
    String body = event.dataAsString().orElseThrow();
    ...
}
```

Types are reverse-DNS (`com.acme.order.placed`), sources a path (`/orders`).

## Streams (`OrderAudit`)

```java
audit.send(order.orderId(), OrderMessages.placed(order));  // keyed, so one key's records stay in order

@HalyxListener("auditor")                                  // a stream consumer: one listener per consumer group
void onRecord(String value) { ... }
```

Producers are idempotent (`idempotent: true`). Don't use `transactional: true` on a stream Azure will run.

## Caches (`IdempotencyKeys`)

```java
@Component
@Profile("cache")
class IdempotencyKeys {
    IdempotencyKeys(@Qualifier("idempotency") Cache<String> keys) { ... }

    Optional<String> claim(String scope, String key, String newId) {
        String k = scope + "/" + key;
        if (keys.putIfAbsent(k, newId, Duration.ofDays(1))) {
            return Optional.empty();                       // claimed: go ahead
        }
        return Optional.of(keys.get(k).orElseThrow(...)); // an earlier request holds it
    }

    void release(String scope, String key) { keys.delete(scope + "/" + key); }   // the write failed: let a retry in
}
```

Counters: `increment(key, delta, ttl)`, such as a per-minute quota keyed `<api key>:<yyyyMMddHHmm>` with a
two-minute expiry. Locks: `tryLock`. Computed results: `getOrLoad`, or `@Cacheable` with `halyx.cache-manager` (set
`trusted-packages` with the `json` or `jdk` codec, and `fail-open: true`). Several caches can share one server with
their own `namespace`.

Counting each delivery once, when a handler increments: claim the message's id first, count, and release the claim
if counting fails, so the retry counts it:

```java
return seen.putIfAbsent(view.viewId(), "1", Duration.ofDays(2)).flatMap(claimed -> claimed
        ? count(view).onErrorResume(e -> seen.delete(view.viewId()).then(Mono.error(e)))
        : Mono.empty());
```

## SQL (`LedgerService`)

`JdbcTemplate` (or JPA, or Spring Data JDBC) on the Halyx `DataSource`, which logs in with the cloud's IAM token per
connection. The schema from Flyway migrations, or `schema.sql` with `spring.sql.init.mode: always` and
`CREATE TABLE IF NOT EXISTS`. Primary keys make retried inserts detectable.

## Blobs and secrets (`ReceiptService`)

```java
receipts.put(BlobKey.of("receipts/" + customerId + "/" + orderId + ".txt"), BlobContent.ofString(text),
        PutOptions.builder().contentType("text/plain").build());
byte[] bytes = receipts.getBytes(key);                     // NotFoundException when absent
String signingKey = secrets.require("receipt-signing-key");   // cached for the store's cache-ttl, so rotation lands
```

Format amounts explicitly before signing or comparing: `amount.setScale(2, RoundingMode.HALF_UP).toPlainString()`.

## Settings that change without a deploy

With `spring.config.import: halyx:settings` and `reload-interval`, the store's names, relative to its prefix, become
properties: the parameter `orders/max-lines` under the prefix becomes `orders.max-lines`.
Read them where they are used, `environment.getProperty("orders.max-lines", Integer.class, 20)`, or through
`@ConfigurationProperties` with Spring Cloud Context: `@Value` fields keep their startup values. Defaults go in the
base document.

## Messages (`OrderMessages`)

One final class writes and parses each body with `dev.halyx.json.Json` (`Json.write(map)`,
`Json.parseObject(json)`). Bodies carry keys and the few fields consumers need, not whole records: they stay under
every cloud's size limit, and consumers read the current record.

## Local development (`LocalDevelopment`)

```java
@Component
@Profile("local")
class LocalDevelopment implements ApplicationRunner {
    public void run(ApplicationArguments args) {
        if (secrets.get("receipt-signing-key").isEmpty()) {
            secrets.put("receipt-signing-key", localKey);   // on a cloud, the infrastructure creates the secret
        }
    }
}
```

## Reactive services

With `halyx-reactor`, every Halyx bean has a reactive view injected by the same qualifier
(`@Qualifier("receipts") ReactiveBlobStore`), repositories extend `ReactiveDocumentRepository`, and
`@HalyxListener` methods may return `Mono`. Check the names in `docs/api/reactive.md` and `docs/spring-boot.md`.
