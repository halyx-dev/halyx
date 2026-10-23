# Portable orders

A Spring Boot 4 orders service that uses every Halyx API, and runs unchanged on AWS, Azure and Google Cloud. One jar
and one image hold all three providers. Spring profiles pick the cloud (`local`, `aws`, `azure` or `gcp`) and the
optional features (`events`, `streams`, `cache` and `sql`). The guide is
[Portable microservice](https://halyx.dev/docs/portable-service.html).

```bash
# in memory, no cloud
mvn -pl halyx-samples/portable-orders -am package -DskipTests
SPRING_PROFILES_ACTIVE=local,events,streams,cache java -jar halyx-samples/portable-orders/target/portable-orders-1.0.1-SNAPSHOT.jar

curl -s -XPOST localhost:8080/customers/ada/orders -H 'Content-Type: application/json' \
     -d '{"lines":[{"sku":"tea","quantity":2,"unitPrice":3.50}]}'
curl -s localhost:8080/customers/ada/orders

# the image, then on a cloud
mvn -pl halyx-samples/portable-orders spring-boot:build-image
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=aws,events,cache,sql -e AWS_REGION=eu-west-2 ... halyx/portable-orders:1.0.1-SNAPSHOT
```

The tests are in `src/test/java/.../e2e`: one suite (`PortableOrdersSuite`) over the HTTP API, run in memory, on each
cloud's emulators, against the image (`-Pimage`) and against a running deployment (`-Dorders.e2e.url=...`).
`../portable-orders-boot3` runs the same sources and tests on Spring Boot 3.5.
