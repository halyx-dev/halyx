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

## Pull requests

- Keep each pull request to one change, with a title that says what it does, such as "Retry Pub/Sub publishes
  on UNAVAILABLE".
- Say what you changed, why, and how you tested it.
- CI must pass: the build, the license headers, and the unit and emulator tests.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
