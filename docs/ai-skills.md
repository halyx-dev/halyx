---
title: AI skills
nav_order: 8
---

# AI skills
{: .no_toc }

Halyx ships skills for AI coding assistants: packaged instructions and reference material for jobs that are mostly
mechanical but easy to get subtly wrong. Migrating a service onto Halyx is one; checking that a Halyx service
really is portable is another. Each skill is a folder of plain Markdown. Claude Code loads it as a skill, and any
other assistant can follow it as instructions.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## The skills

| Skill | Does | Changes code |
|---|---|---|
| [`halyx-ai-migrate`](#halyx-ai-migrate) | migrates a Spring Boot service from a cloud's SDK, or from Spring Cloud AWS, Azure or GCP, to Halyx | yes, one area at a time, after you approve a plan |
| [`halyx-ai-review`](#halyx-ai-review) | reviews a Halyx service for anything that still ties it to one cloud, with a verdict per cloud | no: it writes a report |

They fit together: migrate a service, then review it before calling it portable or deploying it to another cloud.

## Installing and running them

The skills are in the repository under
[`.claude/skills/`](https://github.com/halyx-dev/halyx/tree/main/.claude/skills).

| Assistant | Install | Run |
|---|---|---|
| Claude Code, in one service | copy the skill's folder into the service's `.claude/skills/` | ask for the job ("migrate this service to Halyx", "review this service for portability"), or type the slash command: `/halyx-ai-migrate`, `/halyx-ai-review` |
| Claude Code, for every project | copy the folder into `~/.claude/skills/` | the same |
| Another assistant | point it at the skill's `SKILL.md` as its instructions; its references are linked from there | ask for the job |

Claude Code picks a skill from its description, so asking in your own words works as well as the slash command.
Outside this repository, the skills link to the published docs at https://halyx-dev.github.io/halyx/ and to the
[portable orders sample](https://github.com/halyx-dev/halyx/tree/main/halyx-samples/portable-orders) on GitHub.

## halyx-ai-migrate

Moves an existing Spring Boot service onto Halyx. It covers:
- Spring Cloud AWS, Spring Cloud Azure, Spring Cloud GCP and the raw SDKs
- `S3Template`, `@SqsListener`, `DynamoDbTemplate`, `@ServiceBusListener`, `CosmosRepository` and `PubSubTemplate`
- EventBridge, Event Grid and Eventarc publishers
- Kafka with IAM login
- Redis with IAM, behind `@Cacheable` or `RedisTemplate`
- JDBC with IAM login
- `aws-parameterstore:`, `sm://` and Key Vault property sources

**How it works:**
1. **Inventory:** every cloud use, each with its Halyx equivalent and a risk.
2. **Plan:** whether the existing data is readable as stored, and how old and new instances coexist during the
   rollout. It stops here for your approval.
3. **Migration:** one area per change, with the existing tests kept passing.
4. **Optionally, profiles:** the cloud becomes a Spring profile, plus a `local` profile with no cloud.
5. **Report:** what changed and what behaves differently.

[Migrating to Halyx](migration.md) describes the method, the mappings and the pitfalls in full; it's the same
method by hand.

| Reference | Holds |
|---|---|
| `inventory.md` | how to find every cloud use: build files, imports, annotations, configuration, and hand-written IAM glue |
| `aws.md`, `azure.md`, `gcp.md` | what each cloud API, Spring Cloud template and property becomes |
| `halyx-spring.md` | the Halyx side: dependencies, SDK versions, properties, beans, imports |
| `differences.md` | existing data, behaviour, rollouts, permissions, and running the old and new libraries at once |
| `testing.md` | the tests that prove a migration: old-state, production-profile and packaged-service tests among them |

**How it was tested:** six Spring Cloud services, two per cloud, were migrated step by step on emulators and
containers. Each passed its original tests and started cleanly on what the old version left behind. One per cloud
then ran on a throwaway EKS, GKE and AKS cluster with workload identity. Every trial found something that is now in
the skill, including one Halyx bug, since fixed. The results are in
[Migrating to Halyx](migration.md#trials).

## halyx-ai-review

Answers one question: would this service run, and behave correctly, on each cloud by changing only its profile? It's
read-only and never calls a cloud. It writes `PORTABILITY-REVIEW.md`: a verdict per cloud (*ready*, *ready with
risks* or *not ready*), then each finding with its file and line, the clouds it affects and the fix. It ends with
what was checked, and what only a real-cloud run can show.

Its 29 rules, in [`references/rules.md`](https://github.com/halyx-dev/halyx/blob/main/.claude/skills/halyx-ai-review/references/rules.md):

| Group | Checks |
|---|---|
| Code | native clients outside one isolated class; SDK exceptions Halyx never throws; features a cloud lacks (delays, deduplication, ordering, `nack` delays, presigned URLs, stream transactions); decimals compared by value; payload sizes; consumers relying on cloud-side filters; cloud names in code; bean-name clashes |
| Configuration | cloud settings in the shared configuration; a profile per cloud and a default; names whose shape differs per cloud; Spring settings Halyx's beans make ineffective; credentials in configuration; a no-cloud profile's limits; cache codecs and key namespaces |
| Build | a provider module per target cloud; leftover cloud libraries; SDK artifacts at test scope; cloud-specific runtime pieces such as the Cloud SQL connector |
| Deployment | permissions Halyx needs beyond the old code's; workload identity per cloud; buildpack direct memory; services with no equivalent on another cloud |
| Tests | tests per cloud; containers wired to Boot rather than Halyx; tests of the production profiles; a start of the packaged service |

Each finding is classed by severity:
- **blocker:** the service won't start, or fails, on a cloud it should run on
- **risk:** it runs, but behaves differently or loses data on some cloud
- **hygiene:** it works today, but makes the next cloud harder

The review reads every candidate before reporting it: an `unwrap` in an isolated, guarded class is fine, and the
same call in a controller isn't.

**How it was tested:** on the portable orders sample, and on a copy of it with twelve problems planted across the
rule groups. It found all twelve, and classified them by reading the code. For example, a message delay is a blocker
on SNS and Pub/Sub but fine on Service Bus. On the sample itself, its first finding was a false positive, which
exposed a wrong rule and a stale row in Halyx's own capability table. Both are fixed, and the sample now reports no
blockers or risks. As with any review, a clean report means no leaks were found in the code and configuration, not
that the service has run on each cloud.

## Naming

Skills are named `halyx-ai-<verb>`, so they can't be mistaken for Maven modules, which are `halyx-<module>`. The
prefix also keeps them together next to other teams' skills. A second word is added only where the verb alone is
ambiguous, as in `halyx-ai-add-cloud`. A skill's description decides when an assistant uses it, so the name only
needs to be short to type. Differences between clouds and frameworks go in a skill's reference files, not in a skill
per cloud.

## Writing a new skill

A skill is a folder in `.claude/skills/` with a `SKILL.md`: front matter with its `name` and a `description` that
says what it does and when to use it, then the method. Detail goes in `references/`. Before a skill is listed here,
it gets trial runs like the two above, ideally including one where the outcome isn't known in advance. Each trial
should end with what it found written back into the skill.

Candidates for later:
- generating least-privilege infrastructure and IAM from a service's `halyx.*` configuration
- adding a cloud to a service
- adding the trial-proven tests to an existing service
- throwaway real-cloud runs
- diagnosing startup and runtime errors
