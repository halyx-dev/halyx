---
name: halyx-ai-create
description: Create a new Spring Boot microservice on Halyx that runs unchanged on AWS, Azure and Google Cloud, following the patterns of Halyx's portable orders sample - object storage, document stores and Spring Data repositories, messaging, events, Kafka streams, Redis and Valkey caches, SQL with IAM login, parameters and secrets - with a profile per cloud, a no-cloud local profile, tests on the memory provider and each cloud's emulators, and optionally a Helm chart and the IAM each cloud needs. Use when asked to create, scaffold, generate, bootstrap or start a new service, microservice or API on Halyx, or a new cloud-agnostic, multi-cloud or portable Spring Boot service.
---

# Create a portable Spring Boot service on Halyx

The goal is a new service that runs on AWS, Azure and Google Cloud from one jar, with the cloud chosen by a Spring
profile, and tests that prove it on each cloud's emulators before it ever reaches a cloud.
`halyx-samples/portable-orders` in this repository is the finished shape: read it when unsure how something should
end up, and copy its patterns rather than inventing new ones.

Outside the Halyx repository (the usual case: the skill copied into a workspace or `~/.claude/skills`), the docs are
at https://halyx.dev/docs/ (`docs/<page>.md` here is `https://halyx.dev/docs/<page>.html` there), and the sample is at
https://github.com/halyx-dev/halyx/tree/main/halyx-samples/portable-orders.

Work in phases and **stop for approval after phase 2**. The plan fixes the data model, the resources and their names,
which are expensive to change once data exists.

## Phase 1: understand the service

Ask only what you can't infer from the request. Cover:
- **What it does:** the domain, its operations (the API: REST, or only listeners), and who calls it.
- **Its data:** the entities, and how each is read. Every read must be by key, by a partition and a range of sort keys,
  or by a declared index: list the access patterns, since they decide the keys. Whether anything needs joins, ad hoc
  queries or reports, which points to SQL.
- **What happens asynchronously:** work handed to another component, events other services consume, high-volume
  ordered records, scheduled or retried work.
- **Settings and secrets:** values that change without a deploy, keys and passwords.
- **Targets:** the clouds (all three by default), Spring Boot 4 or 3.5 (4 by default), Java 21 or later, Maven.
- **The deployment pieces wanted:** none, a Helm chart, the IAM each cloud needs, or infrastructure as code.

## Phase 2: plan, then stop

Choose each building block from [building-blocks.md](references/building-blocks.md), which maps each need to a Halyx
resource and to the service on each cloud, with what differs. Present:
1. **The resources:** a table with each `halyx.*` key, its type, what it holds or carries, and the service on AWS,
   Azure and Google Cloud. Every key is a bean name: check it against the classes you are about to create
   ([project.md](references/project.md#names)).
2. **The data model:** each entity as a record, its partition and sort keys, its indexes, and each access pattern
   against the repository method that serves it. Amounts as `BigDecimal` formatted explicitly, or minor units.
3. **The flows:** each asynchronous step, what triggers it, what it writes, and why a redelivery does no harm.
4. **What differs per cloud:** anything [building-blocks.md](references/building-blocks.md#what-differs) flags for the
   plan (message size, delays, ordering, presigned URLs, transactions, services with no emulator), and how the
   service stays correct on each.
5. **The profiles:** the cloud profiles (`local`, `aws`, `azure`, `gcp`), and any optional feature profiles, only for
   resources a deployment may leave out.
6. **The tests** ([testing.md](references/testing.md)) and the **deployment pieces** asked for.
7. **What Halyx doesn't cover** in what was asked, and the proposal for it: the native SDK behind one isolated class
   per cloud, or leaving it out.

Then wait for the user to approve or change the plan.

## Phase 3: scaffold

Follow [project.md](references/project.md) for the build, the package layout and `application.yml`, and
[code.md](references/code.md) for the code. In this order, building after each step:
1. **The build and the application class.** Start it once on the `local` profile with no resources: it proves the
   dependencies and the profile structure before there's code to blame.
2. **The configuration:** the base document with every shared resource, a document per cloud, and the feature
   documents. Resource names come from environment variables with defaults, so a deployment sets only what differs.
3. **The domain:** records, repositories, services and the API, with errors mapped to HTTP statuses.
4. **The asynchronous parts:** publishers, `@HalyxListener` handlers, hooks for optional features.
5. **Local development:** what the `local` profile needs that a cloud's infrastructure would provide, such as
   secrets seeded at startup.

Use only the Halyx API and Spring. No cloud SDK in `src/main`, and no cloud names, ARNs or endpoints in code.

## Phase 4: tests

Follow [testing.md](references/testing.md):
1. One **suite** of end-to-end tests over the service's API, abstract over where the service runs.
2. The suite on the **`local` profile**, in Surefire.
3. The suite on **each target cloud's emulators**, in Failsafe, with the resources created as the infrastructure
   would create them.
4. A **profile test** that resolves each cloud profile without connecting, and a test that starts the **packaged
   jar**.

Run them all. A test that has never failed proves little: break one thing per area (a wrong resource name, a
listener that throws) and check the right test fails, then put it back.

## Phase 5: deployment pieces (if asked)

Follow [deploy.md](references/deploy.md): a Helm chart with values per cloud and workload identity, the
least-privilege permissions per resource on each cloud, and, if asked, infrastructure as code. Name what was not
proven: nothing here has run on a cloud until a real deployment has.

## Phase 6: review and report

Review the service for portability. With the `halyx-ai-review` skill available, run it; otherwise check the list in
[project.md](references/project.md#before-calling-it-done). Fix every blocker and risk it finds, and say which hygiene
findings are left and why. Then report:
- what was created: the resources, the classes, the profiles, the tests and their results per cloud
- what differs per cloud and how the service handles it
- what wasn't tested (services with no emulator, IAM, anything only a real cloud shows), and the next steps

## Rules

- **Don't invent API.** Check every method, property and annotation against the Halyx docs (`docs/api/*.md`,
  `docs/spring-boot.md`, `docs/providers.md`), the source, or the sample, not against memory.
- **Portable by construction.** Shared settings in the base document, cloud settings in the cloud's document, and
  the code free of cloud SDKs. A need with no portable form is isolated, `@Profile`'d per cloud and named in the plan.
- **Every handler can run twice.** Messages, events and records are delivered at least once.
- **No defaults for what only a deployment knows**, and no empty defaults for optional provider properties: a
  missing endpoint should fail at startup, and Halyx reads `""` as a value ([project.md](references/project.md#names)).
- **Tests never reach a cloud.** A profile's `halyx:` import connects with the machine's credentials: the profile
  test swaps the provider for memory ([testing.md](references/testing.md#the-profiles-themselves)).
- **Leave credentials to the default chain.** Workload identity, IAM roles, managed identities and gcloud logins
  need no configuration. Keys belong only in emulator tests.
- **Keep it the size of the request.** Generate what the plan lists, not every Halyx feature: the sample uses all of
  them because it demonstrates them.
