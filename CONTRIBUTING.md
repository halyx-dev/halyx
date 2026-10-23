# Contributing to Halyx

Thanks for your interest in Halyx. Bug reports, documentation fixes, samples and code are all welcome.

## Where to start

- **Questions and ideas:** open a [discussion](https://github.com/halyx-dev/halyx/discussions) rather than an
  issue.
- **Bugs:** open an [issue](https://github.com/halyx-dev/halyx/issues/new/choose) with the Halyx version, the
  module and provider, and the code or steps that show the problem.
- **Security problems:** don't open a public issue. See the [security policy](SECURITY.md).
- **Something to work on:** issues labelled
  [`good first issue`](https://github.com/halyx-dev/halyx/labels/good%20first%20issue) are small and
  self-contained, and [`help wanted`](https://github.com/halyx-dev/halyx/labels/help%20wanted) issues are larger.
  The [roadmap](https://halyx.dev/docs/roadmap.html) lists what's planned.

To take an issue, comment `/assign` on it (or just ask). Each contributor has one issue at a time. Open a draft pull
request within a week, even if it's not finished: issues without a linked pull request after 10 days are unassigned so
someone else can pick them up. Larger `help wanted` issues need a short plan in the issue before they're assigned.

For anything larger than a small fix, comment on the issue or start a discussion first, so we can agree on the
approach before you write the code.

## Building

Java 21 or later, Maven 3.9 or later, and Docker for the integration tests.

```bash
mvn test            # unit tests and the conformance kits on the in-memory provider
mvn verify          # also every conformance kit against the emulators (needs Docker)
mvn -pl halyx-aws -am verify -Dit.test='S3BlobStoreConformanceIT'   # one suite
```

The integration tests are skipped when Docker isn't available. See
[Design, build and test](https://halyx.dev/docs/design.html#build) for how the modules and tests fit together.

## Making a change

- **Keep behaviour the same on every cloud.** Each service has a conformance kit in `halyx-tck` that every
  provider runs. A change in behaviour goes into the kit, so it's checked on every provider. If a provider can't
  support something, declare it as a capability rather than letting it behave differently.
- **Test on the memory provider and the emulators.** CI runs both on every pull request. Real-cloud runs need
  cloud accounts and are done by the maintainers before a release, so you don't need one.
- **Cover Spring Boot** when a change reaches the starter: the auto-configuration is tested on Boot 3.5 and 4.
- **Update the documentation** in `docs/` when you change something users see. It's published at
  [halyx.dev](https://halyx.dev/docs/).
- **Add the license header.** Every `.java`, `pom.xml`, `.yml`, `.sh` and `.tf` file carries the Apache-2.0
  header, and the build fails without it. `mvn -N com.mycila:license-maven-plugin:format` adds any missing ones.
- **Match the surrounding code** in naming, structure and comments.

## Code conventions

The build checks the first group in `mvn validate`, and fails on any of them. Your editor follows most of them through
[`.editorconfig`](.editorconfig).

- No wildcard imports, and no unused ones.
- Tests assert with AssertJ (`assertThat(...)`), not JUnit's `Assertions`.
- No `System.out` or `System.err` in the library: it logs. Samples, benchmarks and tests may print.
- UTF-8, spaces rather than tabs, and a newline at the end of every file.

Reviews check the rest:

- **The public API is the same on every cloud.** Errors are Halyx's exceptions in `dev.halyx.error`, never a cloud
  SDK's; provider differences are capabilities; anything provider-specific goes through `unwrap`.
- **Configuration is immutable.** A method that changes a setting returns a new instance, as `ProviderConfig` does;
  types with several options have a builder.
- **No nulls in the API.** A value that may be missing is an `Optional`. Records are preferred for plain data.
- **Versions live in one place.** Each dependency and plugin version is a property in the parent `pom.xml`, such as
  `${kafka4.version}`; a module refers to the property rather than writing a number. A `dev.halyx` module is either
  managed by the parent or uses `${project.version}`.
- **Every code snippet compiles.** Docs, READMEs and samples use only the public API; check each snippet against the
  code before adding it.
- **Comments say why**, not what the code already says, and are as dense as the code around them.

## Pull requests

- Keep each pull request to one change, with a title that says what it does, such as "Retry Pub/Sub publishes
  on UNAVAILABLE": a sentence, without prefixes such as `docs:` or `fix:`.
- Say what you changed, why, and how you tested it.
- Start from an up-to-date `main`, and rebase if it moves on. After a release, the project version changes in every
  `pom.xml`, so a new module must use the current one.
- CI must pass: the build, the license headers, the code conventions, and the unit and emulator tests.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
