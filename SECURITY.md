# Security policy

## Supported versions

| Version | Security fixes |
|---|---|
| 1.0.x | Yes |

Fixes go into the latest release. Once there is more than one release line, this table lists which ones still get
them.

## Reporting a vulnerability

Please don't report vulnerabilities in public issues. Report them privately, either:

- through GitHub's [private vulnerability reporting](https://github.com/halyx-dev/halyx/security/advisories/new), or
- by email to kamran@kamranzafar.org.

Include the Halyx version, the module and provider involved, and the steps or code that show the problem. You'll get
a reply within five working days. A confirmed vulnerability is fixed in a patch release and announced in a GitHub
security advisory, crediting you unless you'd rather not be named.

## Dependencies

Halyx is built on each cloud's own SDKs and on Spring, Reactor, Netty, Jackson, Lettuce, Kafka and JDBC and R2DBC
drivers, so most vulnerabilities that affect it are in those dependencies.

- **Without Spring Boot:** the provider modules bring patched versions of the libraries the cloud SDKs and clients
  pull in, such as Netty, Jackson, Reactor and Kafka's compression libraries. Import `halyx-bom` to keep them
  aligned.
- **With Spring Boot:** your application's Boot BOM decides the versions of Spring Framework, Reactor, Micrometer,
  Spring for Apache Kafka, Netty and Jackson, so their fixes arrive with your Boot upgrades. Some fixes exist only in
  the lines Boot 4 manages (Spring Framework 7, Reactor 3.8, Micrometer 1.16, Spring for Apache Kafka 4), not in the
  Boot 3.5 lines. A Boot 3.5 application can override them in its own `dependencyManagement`, as the
  [Boot 3.5 sample](halyx-samples/portable-orders-boot3/pom.xml) shows.

The repository is scanned with Snyk. Findings that Halyx can't fix itself are listed in [`.snyk`](.snyk), each with
its reason and an expiry date, so they are reviewed again. At 1.0.0 they are:

| Finding | Why it stays open |
|---|---|
| Spring Framework 6.2 and Spring for Apache Kafka 3.3 | The Boot 3.5 lines. Fixed only in Spring Framework 7 and Spring for Apache Kafka 4 (Boot 4); the application's Boot BOM decides |
| Jackson, Reactor and Micrometer as Boot 3.5 manages them | Used only by the auto-configuration's tests against Boot 3.5; the Halyx modules bring patched versions |
| Logback 1.5 | Comes through `spring-boot-starter`, so Boot decides; the samples bring 1.6.5 |
| Reactor Netty 1.2 | Through the Azure SDK's HTTP client and R2DBC's PostgreSQL driver. The fixes need Netty 4.2, which the AWS and Azure SDKs and Lettuce 6 don't support yet. None applies to how Halyx uses it: no Reactor Netty server, no WebSockets, and the default DNS resolver |
| log4j-api | No fixed version yet |
