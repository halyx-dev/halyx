---
name: migrate-to-halyx
description: Migrate an existing Spring Boot microservice from AWS, Azure or Google Cloud SDKs (Spring Cloud AWS, Spring Cloud Azure, Spring Cloud GCP, or the raw SDKs) to Halyx, so it runs unchanged on any cloud. Use when asked to migrate, port or convert a service to Halyx, to make a service cloud-agnostic or portable, to assess how much work a migration is, or to replace S3Template, SqsTemplate, @SqsListener, DynamoDbTemplate, ServiceBusTemplate, @ServiceBusListener, CosmosRepository, PubSubTemplate, Firestore repositories, aws-parameterstore:, sm:// or Key Vault property sources with Halyx.
---

# Migrate a Spring Boot service to Halyx

The goal is the same service, behaving the same way, with its cloud services reached through Halyx's API and
`halyx.*` properties. The cloud then becomes a Spring profile, not code. `halyx-samples/portable-orders` in this
repository is the finished shape to aim for; read it when unsure how something should end up.

Outside the Halyx repository (the usual case: the skill copied into the service's repository or `~/.claude/skills`),
the docs are at https://halyx-dev.github.io/halyx/ (`docs/<page>.md` here is `<page>.html` there), and the sample is
at https://github.com/halyx-dev/halyx/tree/main/halyx-samples/portable-orders.

Work in phases and **stop for approval after phase 2**. A migration touches data, messaging and credentials; the
plan is where mistakes are cheap.

## Phase 1: inventory (read only)

Find every place the service touches a cloud. Use the detection patterns in
[references/inventory.md](references/inventory.md): build files, imports, annotations, injected clients, properties
and config imports. Also look at tests, Testcontainers or LocalStack setups, Helm charts and Terraform, which often
name the resources.

For each use, record:
- where it is (file and line)
- the cloud service and how it is used (the client, template, repository or annotation, and the operations called)
- the Halyx equivalent, from the cloud's mapping file: [aws.md](references/aws.md), [azure.md](references/azure.md)
  or [gcp.md](references/gcp.md)
- the risk: **none** (a direct mapping), **behaviour** (works, but differs; see
  [differences.md](references/differences.md)), **data** (existing data must be readable: document stores especially),
  or **no equivalent** (stays on the native client through `unwrap`, or stays as it is)

## Phase 2: plan, then stop

Present:
1. The inventory table.
2. What stays out of scope, and why: services Halyx doesn't cover (see "No Halyx equivalent" in each mapping file),
   features with no portable form, and anything you are unsure of.
3. **Data compatibility** for each document store and blob store: can Halyx read the existing data as it is
   stored? Check it against [differences.md](references/differences.md#existing-data). If it can't, propose either a
   data migration or leaving that store on its native client for now. Never quietly change a storage layout.
4. The order of the steps (below), and the tests that will prove each one.
5. The target configuration: the `halyx.*` properties and bean names, and how the cloud will be chosen (profiles).

Then wait for the user to approve or change the plan.

## Phase 3: migrate one area at a time

Order, from lowest to highest risk (skip what the service doesn't use):
1. **Dependencies:** the Halyx BOM, the starter and the provider modules
   ([halyx-spring.md](references/halyx-spring.md#dependencies)). Keep the old dependencies until their last use is gone,
   but check the resolved SDK versions (`mvn dependency:tree`). The Spring Cloud AWS, Azure and GCP BOMs pin their own,
   often older, cloud SDKs, which win over what Halyx needs. While both are present, import the cloud SDK's BOM at
   Halyx's version first ([halyx-spring.md](references/halyx-spring.md#sdk-versions)), and drop it with the old BOM.
2. **Configuration and secrets:** `spring.config.import: halyx:<store>` in place of the cloud's config import.
3. **Object storage**
4. **Documents:** a Spring Data `DocumentRepository`, or `DocumentStore`
5. **Messaging:** `Publisher` and `@HalyxListener`
6. **Events:** `EventPublisher` and CloudEvents
7. **Streams:** `halyx.spring-kafka` (keeps `KafkaTemplate` and `@KafkaListener`), or `StreamProducer` and
   `@HalyxListener`
8. **Caches:** `halyx.caches` beans, or `halyx.cache-manager` behind `@Cacheable`
9. **SQL:** `halyx.databases`, which gives JPA, JDBC and Flyway a `DataSource` with IAM login

For each area:
- Replace the code with the Halyx API and the configuration with `halyx.*` properties
  ([halyx-spring.md](references/halyx-spring.md)). Inject beans by `@Qualifier` with the `halyx.*` key as the bean
  name.
- Keep resource names the same (`bucket`, `table`, `name` and so on), so the service uses the same cloud
  resources.
- Map errors to Halyx's exceptions (`NotFoundException`, `PreconditionFailedException` and so on), not to SDK
  exceptions or status codes.
- Remove the cloud SDK code for that area. Keep a native client only where the plan says so, obtained through
  `unwrap`, and isolate it behind one class.
- Build and run the existing tests. Then add or adapt tests as [testing.md](references/testing.md) describes:
  memory-provider tests first, then the emulator tests the project already has.
- Summarise what changed and what behaves differently, then move to the next area.

## Phase 4: make the cloud a profile

Once no cloud SDK is left in the code:
- Move the provider and the cloud-specific settings into a document per cloud in `application.yml`
  (`spring.config.activate.on-profile: aws` and so on). Keep the shared `halyx.*` resources in the base document,
  with names from environment variables where they differ per deployment. `portable-orders`' `application.yml`
  shows the pattern.
- Set `spring.profiles.default` to the cloud it runs on today, so deployments that set no profile keep working.
- Add a `local` profile on the `memory` provider, so the service starts with no cloud at all.
- Keep only the provider modules the user wants: all of them for one artifact that runs anywhere, or the one in use.

Only do this phase if the user wants the service portable, not just migrated.

## Phase 5: report

- What was migrated, and what wasn't, and why
- Behaviour differences the service now has to tolerate, per cloud
- Tests added, and their results; anything not tested (for example, real-cloud runs)
- Follow-ups: data migrations, IAM changes (Halyx's health checks and listeners need some permissions; see
  [differences.md](references/differences.md#permissions)), removed dependencies

## Rules

- Don't change behaviour silently. If Halyx works differently, such as nack delays, ordering or decimals, say so in
  the plan and in the code review, and keep the service correct.
- Don't invent API. Check method and property names against the Halyx docs (`docs/api/*.md`,
  `docs/spring-boot.md`, `docs/providers.md`) or the source, and not against memory.
- A `halyx.*` key is a bean name. It must not clash with the service's own beans: a class `OrderListener` and a
  subscription `order-listener` are fine, but a class `Fulfilment` and a subscription `fulfilment` aren't.
- Leave credentials to the default chain, which covers IAM roles, workload identity, managed identities and gcloud.
  Configure `credentials` only when the service used explicit keys, and then from a secret, never inline.
- One area per change, so each can be reviewed and reverted on its own.
