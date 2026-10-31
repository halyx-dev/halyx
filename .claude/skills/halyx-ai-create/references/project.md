# The project: build, layout and configuration

## The build

One Maven module, with Spring Boot's parent, so Boot manages Spring's versions as in any Boot application, and
Halyx's BOM manages Halyx's.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version><!-- 4.1.x, or 3.5.x --></version>
  <relativePath/>
</parent>

<properties>
  <java.version>21</java.version>
  <halyx.version><!-- the latest release on Maven Central --></halyx.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.halyx</groupId>
      <artifactId>halyx-bom</artifactId>
      <version>${halyx.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

| Dependency | When |
|---|---|
| `dev.halyx:halyx-spring-boot-starter` | always |
| `dev.halyx:halyx-aws`, `halyx-azure`, `halyx-gcp` | one per target cloud; all three for one jar that runs anywhere |
| `dev.halyx:halyx-spring-data-document` | Spring Data repositories over document stores |
| `dev.halyx:halyx-reactor`, and `halyx-r2dbc` for R2DBC | a reactive (WebFlux) service |
| `spring-boot-starter-webmvc` (Boot 4) or `spring-boot-starter-web` (Boot 3.5) | a REST API; `spring-boot-starter-webflux` for a reactive one |
| `spring-boot-starter-actuator` | always: health, with Halyx's `halyx` contributor, and the probes a deployment uses |
| `spring-boot-starter-jdbc` (or `-data-jpa`), `org.postgresql:postgresql` at runtime scope | SQL |
| `com.google.cloud.sql:postgres-socket-factory` at runtime scope (Halyx's BOM manages its version) | SQL on Google Cloud through the Cloud SQL connector |
| `org.flywaydb:flyway-core` and `flyway-database-postgresql` | schema migrations, rather than `schema.sql` |
| `spring-boot-starter-test`, `org.testcontainers:testcontainers-junit-jupiter`, `-kafka`, `-postgresql` at test scope | the tests |

- **Boot 3.5 manages Testcontainers 1.x, which can't talk to Docker 29 and later.** Set
  `<testcontainers.version>` to Halyx's (2.0.x) in the properties. With Boot's parent, the property is what
  works: a BOM imported in the service's own `dependencyManagement` loses to the parent's entries.
- **Never declare a cloud SDK artifact** (`software.amazon.awssdk`, `com.azure`, `com.google.cloud`) **at test scope**
  for the tests' own clients. It demotes the provider module's dependency on the same artifact, every test passes,
  and the packaged jar fails with `NoClassDefFoundError`. The tests use the SDK clients the provider modules bring.
- Plugins: `spring-boot-maven-plugin` (repackage; `build-image` for an image), and `maven-failsafe-plugin` for the
  `*IT` tests. Boot's parent configures both; declare them in `<build><plugins>` to switch them on.
- **Boot's parent decides the versions it manages, Halyx's security pins included.** A Boot 3.5 service runs
  Boot's Netty (4.1.135 against Halyx's patched 4.1.139) and Reactor 3.7 (Halyx brings 3.8.7, whose fix matters to
  Halyx's reactive batching); a Boot 4.1 service runs Lettuce 7.5 and Netty 4.2, where Halyx is built on Lettuce 6.9
  and Netty 4.1 (the trials ran caches, `@Cacheable` and streams on them without trouble). Run
  `mvn dependency:list` once, name what resolves in the report, and offer the overrides, as the sample applications
  carry them (`halyx-samples/portable-orders*/pom.xml`). Under Boot's parent, a version property works for Netty
  (`<netty.version>4.1.139.Final</netty.version>`). For Reactor, add an explicit `io.projectreactor:reactor-core`
  entry at 3.8.7 to `dependencyManagement`, not `<reactor-bom.version>`: Reactor's 2025 BOM also moves Reactor Netty
  to 1.3, which needs Netty 4.2 and breaks the Azure SDK's transport on 4.1.

## Layout

One package, named after the service, as the sample has: `dev.halyx.samples.orders` there,
`com.<org>.<service>` here.

| Class | Is |
|---|---|
| `<Service>Application` | `@SpringBootApplication` |
| `<Entity>` records, `<Entity>Repository` | the document model and its Spring Data repository |
| `<Entity>Service` | the operations: validation, writes, then announcements |
| `<Entity>Controller` | the API, with records for requests and views, and `@ExceptionHandler`s returning `ProblemDetail` |
| a class per listener, named for what it does (`OrderFulfilment`, `OrderAudit`) | `@HalyxListener` handlers |
| a class per optional feature, `@Profile("<feature>")` | the feature's beans and listeners; the core reaches it through a hook interface or `ObjectProvider` |
| `<Entity>Messages` | the JSON of messages, events and records, written and parsed in one place with `dev.halyx.json.Json` |
| `LocalDevelopment`, `@Profile("local")` | what infrastructure would provide on a cloud, such as a secret seeded at startup |
| `*Exception` | the service's own errors, mapped to statuses in the controller |

## Names

Every `halyx.*` key, in every group, is a bean name. It must not equal the bean name of any class in the service
(the class name in lower camel case) or any `@Bean` method, or startup fails naming both. Name keys for the resource
(`orders`, `receipts`, `order-placed`, `fulfilment`, `idempotency`, `ledger`) and classes for their role
(`OrderService`, `ReceiptService`, `OrderFulfilment`). Keys with a dash can never clash with a class.

Cloud resource names come from environment variables named `<SERVICE>_<RESOURCE>`, with defaults of
`<service>-<resource>`, so a deployment that uses the default names sets nothing:
`bucket: ${ORDERS_RECEIPTS_BUCKET:portable-orders-receipts}`.

- **Values only a deployment knows have no default:** endpoints, accounts, bootstrap servers
  (`endpoint: ${ORDERS_CACHE_ENDPOINT}`). A deployment that forgets one then fails at startup, instead of quietly
  using a placeholder such as `redis://memory:6379`; the `local` profile sets its own.
- **Optional provider or resource `properties` that only some deployments set** (a Cloud SQL `gcp.sql.instance`)
  can be `${VAR:}`: a blank value is no value. A file a property names, such as a Memorystore CA in
  `redis.ca-certificate`, goes in the deployment's configuration (the Helm chart's `config` and `files`), next to the
  file.

## application.yml

Documents in this order. The sample's `application.yml` is the complete example.

```yaml
# 1. Shared: the application name, actuator, the service's own settings, and every Halyx resource with its name
spring:
  application:
    name: <service>
management:
  endpoints: { web: { exposure: { include: health,info } } }
  endpoint: { health: { show-details: always, probes: { enabled: true } } }
halyx:
  blob-stores:
    receipts:
      bucket: ${<SERVICE>_RECEIPTS_BUCKET:<service>-receipts}
  # document-stores, topics, subscriptions, parameter-stores ... shared, with names from the environment

---
# 2. A document per cloud: the provider, named after the cloud, and what only that cloud needs
spring:
  config:
    activate:
      on-profile: local
    import: optional:halyx:settings         # the memory store starts empty
halyx:
  providers:
    local:
      type: memory
  subscriptions:
    fulfilment:
      topic: ${<SERVICE>_TOPIC:<service>-placed}   # the memory broker binds subscriptions to topics itself
---
spring:
  config:
    activate:
      on-profile: aws
    import: halyx:settings
halyx:
  providers:
    aws:
      type: aws
      region: ${AWS_REGION:eu-west-2}
---
spring:
  config:
    activate:
      on-profile: azure
    import: halyx:settings
halyx:
  providers:
    azure:
      type: azure
      properties:                            # only the accounts the service uses
        azure.storage.account: ${<SERVICE>_STORAGE_ACCOUNT}
        azure.cosmos.endpoint: ${<SERVICE>_COSMOS_ENDPOINT}
        azure.cosmos.database: ${<SERVICE>_COSMOS_DATABASE:<service>}
        azure.servicebus.namespace: ${<SERVICE>_SERVICEBUS_NAMESPACE}
  subscriptions:
    fulfilment:
      name: ${<SERVICE>_FULFILMENT_SUBSCRIPTION:<service>-placed/subscriptions/fulfilment}
  parameter-stores:
    settings:
      location: ${<SERVICE>_APPCONFIG_ENDPOINT}
    secrets:
      location: ${<SERVICE>_KEYVAULT_URL}
---
spring:
  config:
    activate:
      on-profile: gcp
    import: halyx:settings
halyx:
  providers:
    gcp:
      type: gcp
      properties:
        gcp.project-id: ${GOOGLE_CLOUD_PROJECT}
  parameter-stores:
    settings:
      location: ${<SERVICE>_PARAMETER_LOCATION:global}

---
# 3. A document per optional feature, then `<cloud> & <feature>` documents for what differs per cloud
```

Per cloud, the settings that differ:

| Resource | AWS | Azure | Google Cloud |
|---|---|---|---|
| Provider | `region` | `properties`: `azure.storage.account`, `azure.cosmos.endpoint`, `azure.cosmos.database`, `azure.servicebus.namespace`, for the services used | `properties.gcp.project-id` |
| Subscription `name` | the SQS queue | `<topic>/subscriptions/<name>` | the Pub/Sub subscription |
| Event bus | the bus name | `properties.azure.eventgrid.endpoint` on the bus, and on its subscription with `topic: <namespace topic>` | `projects/<p>/locations/<l>/messageBuses/<id>`, or the id with `properties.gcp.eventarc.location` |
| Parameter store `location` | none | the App Configuration endpoint (`parameters`), the Key Vault URL (`secrets`) | `global` or a region (`parameters`) |
| Cache `auth` | `type: iam`, `username` the ElastiCache user id, `properties.aws.elasticache.cache-name` | `type: iam`, `username` the identity's object id | `type: iam`; a `rediss://` endpoint, and `properties.redis.ca-certificate` with the instance's CA file, in the deployment's configuration |
| Database | `host`, `auth: { type: iam, username }` | `host`, `auth: { type: iam, username }` (the Entra ID name) | `host`, or through the Cloud SQL connector `properties.gcp.sql.instance: ${<SERVICE>_SQL_INSTANCE:}` (`<project>:<region>:<instance>`) with `host: ${<SERVICE>_DB_HOST:}`; `auth: { type: iam, username }` |
| Stream cluster | `bootstrap-servers` (port 9098) | `<namespace>.servicebus.windows.net:9093` | the cluster's bootstrap address |
| Function `properties` | none; optional `aws.lambda.qualifier` (a version or alias) | `azure.functions.endpoint` (the function app's URL, no default), and `azure.functions.audience` (App Service Authentication's Entra ID app) or `azure.functions.key: ${<function>-key}` from a secrets store's config import | `gcp.functions.region` for the default URL, or `gcp.functions.url` (a `run.app` URL) |

A function's `name` and `timeout` go in the base document (`name: ${<SERVICE>_RESIZE_FUNCTION:<service>-resize}`),
its login and location in each cloud's document. On `local`, the function is a handler registered at startup
([code.md](code.md#functions)).

The `local` profile: the memory provider has every service but SQL, and its streams run in process. Keep
`halyx.databases` and `halyx.spring-kafka` out of what `local` sees. When SQL is optional, a feature document holds
them. When it's the service's core, one document holds them for every other profile, and `local` turns Boot's
`DataSource` back on:

```yaml
---
spring:
  config:
    activate:
      on-profile: "!local"
halyx:
  databases:
    inventory-db: { engine: postgresql, host: "${INVENTORY_DB_HOST:}", database: inventory, auth: { type: iam, username: inventory_app } }
---
spring:
  config:
    activate:
      on-profile: local
  autoconfigure:
    exclude: []                      # Boot's DataSource again, which the shared document excluded
  datasource:
    url: ${INVENTORY_LOCAL_DB_URL:jdbc:postgresql://localhost:5432/inventory}
``` Caches on `local` use any `redis://` endpoint with `auth.type: none`; stream clusters `memory:9092` with
`auth.type: none`.

Exclude Boot's `DataSourceAutoConfiguration` in the shared document when SQL is optional, under both its Boot 3 and
Boot 4 names, as the sample does; a profile without SQL then starts without a `DataSource`.

## Before calling it done

The checks `halyx-ai-review` runs, in short. Each should hold:
- No `software.amazon.awssdk`, `com.azure` or `com.google.cloud` import in `src/main` (the Cloud SQL socket factory
  aside), no `unwrap(` outside one isolated, `@Profile`'d class, and no ARNs, endpoints or cloud domains in code.
- Halyx exceptions caught (`NotFoundException`, `PreconditionFailedException`), never SDK ones.
- No messaging feature a target cloud lacks (delays, deduplication, ordering without the entity set up for it), no
  stream transactions on Azure, and presigned URLs only with the Google Cloud grant.
- No function `trigger` (Lambda only), function keys only from a secrets store, and `idempotent: true` only on
  functions that are.
- Decimals formatted explicitly and compared with `compareTo`.
- Event listeners check the event's type and source.
- No `halyx.*` key equal to a bean name.
- The base document holds no provider, region, endpoint or cloud property; each target cloud has a profile; the
  `local` profile sees no `halyx.databases` or `halyx.spring-kafka`.
- No credentials in any profile a deployment uses.
- `halyx.cache-manager` with `codec: json` or `jdk` sets `trusted-packages`.
- A provider module per target cloud; no cloud SDK at test scope; the Cloud SQL socket factory at runtime scope when
  Google Cloud runs SQL.
- Tests run the same suite on each target cloud's emulators, the profiles resolve, and the packaged jar starts.
