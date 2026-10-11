---
title: Roadmap
nav_order: 8
---

# Roadmap
{: .no_toc }

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Done

| | Milestone | What it delivered |
|---|---|---|
| 1 | Core and object storage | the portable API, provider SPI, error model and interceptors; blob storage on S3, Azure Blob Storage and Cloud Storage; conformance kits and emulator tests |
| 2 | Caches | one Redis and Valkey driver with cloud logins: ElastiCache IAM, Entra ID, Memorystore IAM |
| 3 | SQL databases | a pooled `DataSource` with cloud IAM login and token refresh: RDS IAM, Entra ID, Cloud SQL, and the Cloud SQL connector |
| 4 | Parameters and secrets | SSM Parameter Store, Secrets Manager, App Configuration, Key Vault, Parameter Manager, Secret Manager and HashiCorp Vault; Spring configuration import with reload |
| 5 | NoSQL documents | DynamoDB, Cosmos DB and Firestore |
| 6 | Spring Boot | the starter, properties, auto-configuration, health, Micrometer, Boot 3.5 and 4 |
| 7 | Messaging | SNS and SQS, Service Bus, Pub/Sub; listeners, claim checks, tracing |
| 8 | Production gaps | NoSQL secondary indexes and transactions, batched acks, Service Bus sessions |
| 9 | Real-cloud testing | the conformance kits on real AWS, Google Cloud and Azure, in three tiers each, on throwaway infrastructure. [Results](cloud-testing/index.md) |

## Next

### Release 0.1.0

Publish the first release to Maven Central:

- [x] Apache-2.0 license, with headers on every source file
- [x] the `org.kamranzafar.cirrus` group id
- [x] a full build and test pass, on emulators and on real clouds
- [ ] project metadata: URL, SCM and developers
- [ ] source and javadoc jars, signing, and the Central publishing plugin
- [ ] JPMS module descriptors
- [ ] continuous integration
- [ ] API compatibility checks with japicmp, from the release after 0.1.0

### Real-cloud test follow-ups

- [ ] AWS tier 3: check the ElastiCache `Connect` permission scoping in the test IAM policy
- [ ] Azure Database for MySQL with Entra ID, which needs a directory-level grant for the server's identity

## On demand

Planned, and prioritised by demand:

- **Spring Data repositories over document stores** (`cirrus-spring-data`): CRUD by composite id
  (`@PartitionKey`, `@SortKey`), `@Version` optimistic locking, derived queries on the partition key, sort
  range and declared indexes, and keyset scrolling (`Window` and `ScrollPosition`). Not planned: table
  scans, general `@Transactional` and reactive repositories.
- **R2DBC** for reactive Spring Data, with cloud IAM login.
- **Aurora fast failover** through the AWS Advanced JDBC Wrapper.
- **Nested field paths** in NoSQL updates.
- **Cross-instance cache stampede protection**: a single-flight `getOrLoad`.
- **Object storage extras**: archive restore, KMS encryption, versioning, tags and retention.

## Not planned

These were considered and dropped, because they work already or don't earn their weight:

| Idea | Why not |
|---|---|
| cache endpoint discovery through cloud APIs | you configure the endpoint; discovery adds permissions and failure modes |
| push notifications for parameter changes | polling `watch` and configuration reload cover it |
| SSM and Secret Manager labels | rarely needed; `unwrap` covers them |
| EventBridge and Event Grid | event routing, rather than messaging |
| GCS parallel composite uploads, the AWS CRT client, an async API | the parallel transfer engines already cover large objects |
| RDS Proxy support | nothing to add: point `host` at the proxy endpoint, with IAM login if it requires it |
