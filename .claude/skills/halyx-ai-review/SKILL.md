---
name: halyx-ai-review
description: Review a Spring Boot service that uses Halyx for anything that still ties it to one cloud or will break on another, and report each finding with its file, why it matters and the fix, plus a verdict per cloud (AWS, Azure, Google Cloud). Read-only. Use when asked to review, audit or check a Halyx service for portability, cloud lock-in, readiness for another cloud, or before calling a service portable, adding a cloud, or deploying it to a new one.
---

# Review a Halyx service for portability

The question is: **would this service run, and behave correctly, on each cloud by changing only its profile?**
Halyx's API makes most of that true, but services leak in predictable places: native clients outside one class,
cloud settings in the shared configuration, features one cloud lacks, decimals, bean names, permissions, and tests
that only ever run one cloud. [references/rules.md](references/rules.md) lists every check, with how to detect it.

This review is **read-only**: it changes nothing, and it never calls a cloud. Outside the Halyx repository the
docs are at https://halyx-dev.github.io/halyx/ (`docs/<page>.md` here is `<page>.html` there).

## Steps

1. **Scope.** Find the build file, the main and test sources, every `application*.yml` and `*.properties` file, and
   the deployment files (Helm, Kubernetes manifests, Terraform, Dockerfiles). Ask which clouds the service is meant
   to run on, unless that's clear from its profiles; review against all three by default.
2. **Inventory the Halyx surface.** List every `halyx.*` key in the configuration, the beans injected with
   `@Qualifier`, every `@HalyxListener`, and every `unwrap(`. This is what the rules check against.
3. **Run every rule** in [rules.md](references/rules.md), in order: code, configuration, build, deployment,
   tests. Use the detection given for each, then read the hits: a grep finds candidates, not findings. Record a
   finding only when you've read the code or configuration and it does what the rule describes.
4. **Classify** each finding:
   - **blocker:** the service won't start, or fails, on a cloud it should run on
   - **risk:** it runs, but behaves differently or loses data on some cloud
   - **hygiene:** it works everywhere today, but makes the next cloud, or the next change, harder
5. **Decide the verdict per cloud:** *ready* (no blockers or risks for it), *ready with risks* (risks only), or
   *not ready* (any blocker). A cloud with no profile at all is *not ready*: say what the profile needs.
6. **Write the report** (below) to `PORTABILITY-REVIEW.md` at the service's root, and summarise it in the reply.

## The report

```markdown
# <service>: portability review

| Cloud | Verdict | Blockers | Risks |
|---|---|---|---|
| AWS | ready / ready with risks / not ready | n | n |
| Azure | ... |
| Google Cloud | ... |

## Findings

### <severity>: <one-line claim>  (rule <id>)
`path/to/File.java:42`. What the code or configuration does, and what happens on which cloud. The fix, concretely.

## What was checked

The rules run, and what each covered (for example, "C3: 2 publishers, neither sets delays"). Rules that didn't
apply, and why. What a review can't establish: anything only a real-cloud run shows (IAM, services with no
emulator), and whether the tests actually run per cloud.
```

Order the findings by severity, blockers first. Give every finding a location, the clouds it affects, and a fix
someone can apply without asking. Don't report a finding twice under two rules; pick the more specific one.

## Rules of the review

- **Read before reporting.** `unwrap(` in an isolated class marked as cloud-specific is fine; the same call in
  a controller is a finding. A `BigDecimal.equals` on an amount that never touches a store is fine.
- **Check against Halyx, not memory.** Capability names, properties and behaviour are in `docs/api/*.md`,
  `docs/spring-boot.md` and `docs/providers.md`, or the source. If unsure whether a provider supports something,
  say so in the report rather than guessing.
- **Don't fix anything.** Offer to apply the fixes after the report, one finding at a time.
- **Name what wasn't proven.** A clean review means no leaks were found in the code and configuration. It doesn't
  mean the service has run on each cloud.
