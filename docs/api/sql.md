---
title: SQL databases
parent: API guide
nav_order: 5
---

# SQL databases: `Database`
{: .no_toc }

A pooled JDBC `DataSource` (HikariCP) whose connections log in with the cloud's identity instead of a
stored password, on **Amazon RDS and Aurora**, **Azure Database for PostgreSQL and MySQL** and **Azure
SQL**, and **Google Cloud SQL**. Passwords work everywhere too, including any self-hosted PostgreSQL,
MySQL or SQL Server.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## A pool with IAM login

```java
Database orders = halyx.provider("aws").database(SqlConfig.builder(SqlEngine.POSTGRESQL)
        .host("orders.cluster-abc.eu-west-1.rds.amazonaws.com")
        .database("orders")
        .auth(SqlAuth.iam("orders_app"))
        .pool(new PoolSettings(20, 5, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30)))
        .build());

DataSource dataSource = orders.dataSource();   // to JDBC, JPA, jOOQ, Flyway, Liquibase
boolean ok = orders.isHealthy(2);               // borrows a connection and checks it within 2 seconds
```

`database(config)` opens a new pool on every call, so create the `Database` once and keep it. Closing it,
or closing `Halyx`, closes the pool. The pool starts on first use, so the application can start while
the database is unreachable.

`PoolSettings(maxSize, minIdle, connectionTimeout, idleTimeout, maxLifetime)` sizes the pool. Without
one, `PoolSettings.DEFAULTS` applies: 10 connections, all kept idle, a 30-second connection timeout, a
10-minute idle timeout and a 30-minute maximum lifetime.

## How token login works

Tokens are fetched when a new physical connection opens, and cached until shortly before they expire.
Established connections stay logged in, so token lifetimes never interrupt queries. IAM logins turn on
TLS (`sslmode=require`, `sslMode=REQUIRED` or `encrypt=true`).

| Provider | `SqlAuth.iam(...)` | Engines | The username is |
|---|---|---|---|
| AWS | RDS IAM token (SigV4, signed locally, 15 minutes) | PostgreSQL, MySQL | the database user granted `rds_iam` |
| Azure | Microsoft Entra ID access token | PostgreSQL, MySQL, Azure SQL | the identity's database role; not used for Azure SQL (`SqlAuth.iam()`), where the token is the driver's `accessToken` |
| Google Cloud | OAuth token with the `sqlservice.login` scope | PostgreSQL, MySQL | derived from the service account if omitted (`app@project.iam` on PostgreSQL, `app` on MySQL) |

{: .important }
IAM database users on MySQL (RDS and Cloud SQL) start with no privileges. Until they're granted some,
for example `GRANT SELECT ON app.* TO 'app'@'%'`, even selecting the database in the JDBC URL is refused.
PostgreSQL lets every user connect.

## Passwords and rotated secrets

`SqlAuth.password(user, supplier)` asks the supplier for every new connection, so a rotated secret takes
effect as connections are replaced (`maxLifetime`, 30 minutes by default) without a restart:

```java
ParameterStore secrets = halyx.provider("aws").parameterStore(
        ParameterStoreConfig.secrets().cacheTtl(Duration.ofMinutes(5)).build());

Database reporting = halyx.provider("aws").database(SqlConfig.builder(SqlEngine.MYSQL)
        .host("reporting.abc.eu-west-1.rds.amazonaws.com")
        .database("reporting")
        .auth(SqlAuth.password("app", () -> secrets.require("reporting/db", "password")))
        .build());
```

## Cloud specifics

**RDS Proxy** works as is: use the proxy endpoint as `host`, with `SqlAuth.iam(...)` if the proxy requires
IAM authentication.

**Cloud SQL Java connector.** On Google Cloud, set the instance connection name instead of a host. The
connector handles TLS and IAM itself and needs no authorized networks:

```java
Database db = halyx.provider("gcp").database(SqlConfig.builder(SqlEngine.POSTGRESQL)
        .property(GcpProperties.SQL_INSTANCE, "acme-prod:europe-west2:orders")
        .database("orders")
        .auth(SqlAuth.iam())
        .build());
```

Add `com.google.cloud.sql:postgres-socket-factory` or `com.google.cloud.sql:mysql-socket-factory-connector-j-8`
to use it.

**TLS verification.** To verify the server certificate instead of only encrypting, override the TLS
property with `jdbcProperty(...)`, for example `jdbcProperty("sslmode", "verify-full")` with the cloud's CA
bundle.

**Driver.** The JDBC driver is your dependency: `org.postgresql:postgresql`, `com.mysql:mysql-connector-j`
or `com.microsoft.sqlserver:mssql-jdbc`.

## Login latency

A token login costs more than a password login, because the database checks every token. Measured from
the same client:

| | Password | Token, warm | First token login on a new server |
|---|---|---|---|
| Amazon RDS | about 0.35 s | 0.5 to 1.1 s | 5 to 6 s |
| Cloud SQL (PostgreSQL) | about 0.34 s | about 0.47 s | about 1.4 s |
| Azure Database for PostgreSQL | | about 0.35 s | about 4.4 s |
| Azure SQL | | about 0.31 s | about 1.0 s |

Pooled connections pay this only when they are opened. To keep it off the first request, set `minIdle`
above zero so the pool opens connections in the background, and keep `maxLifetime` long. RDS also limits
how fast new IAM connections can be opened, so pool rather than connect per request. The full
measurements are in [Cloud testing](../cloud-testing/index.md).

## Spring Boot

Every `halyx.databases.<name>` entry becomes a `DataSource` bean, covered by Spring Boot's JDBC health
check and Hikari metrics. Spring Data JPA and JDBC, `JdbcTemplate`, Flyway and Liquibase use the primary
Halyx `DataSource` unchanged. See [Spring Boot](../spring-boot.md#sql-databases).
