---
title: AWS
parent: Cloud testing
nav_order: 1
---

# Amazon Web Services: real-cloud results
{: .no_toc }

Results of running the Halyx conformance kits against real Amazon Web Services services in `ap-southeast-2` (Sydney), acting as
the `halyx_test` IAM user. Every run creates its own throwaway resources and deletes them afterwards. See
[Cloud testing](index.md) for how the runs work.

| Tier | Services | Latest result |
|---|---|---|
| [1](#tier-1-latest-result) | S3, DynamoDB, SSM Parameter Store, Secrets Manager, SNS, SQS; Spring Data repositories and Spring Boot on DynamoDB | 179/182 passed, 1 failed (SSM watch), 2 skipped (expected); Spring **2/2** |
| [2](#tier-2-latest-result) | RDS for PostgreSQL and MySQL with IAM auth | **12/12** |
| [3](#tier-3-latest-result) | ElastiCache for Valkey with IAM auth | 52/53: the IAM policy still needs checking |
| [4](#tier-4-not-run) | Streams on MSK Serverless with IAM | not run: the test account's Free plan doesn't allow MSK |

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## How a tier 1 run works

```bash
AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
# a single suite:
IT_TEST='RealAwsIT$SecretsManager' AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
```

1. **Provision.** Terraform (`infra/aws/tier1`) creates one S3 bucket and two DynamoDB tables. Their names
   include a fresh run id (`r<yyMMddHHmm><hex>`), and everything is tagged `halyx-test=<run id>`.
2. **Test.** `RealAwsIT` runs the conformance kits, and is enabled only by `-Dhalyx.real.aws=true`.
   The tests create SNS topics, SQS queues, SSM parameters and secrets themselves, then delete them.
3. **Tear down.** This runs on exit, failure or Ctrl-C. A sweep deletes anything the tests left behind:
   - SNS topics and SQS queues named `halyx-<run>-*`
   - SSM parameters and secrets under `tck-*` created since the run started
     (secrets are deleted without recovery)

   Then `terraform destroy` runs, and a check confirms that nothing tagged with the run id, and none of
   the bucket, tables, topics or queues, is left.

Credentials come from the named AWS profile and never from the repository.

## Tier 1: latest result

| | |
|---|---|
| Date | 2026-10-16, 01:38–01:47 UTC (9 min including provision and teardown) |
| Run id | `r261016123896d3` |
| Region | `ap-southeast-2` |
| Toolchain | Java 25.0.2, Maven 3.9.14, Terraform 1.5.7; Spring Boot 3.5.16 and 4.1.1 |
| Result | 179 passed, **1 failed** (SSM watch, see the run history), 2 skipped (expected); Spring Boot repository test passed on Boot 3 and 4 |
| Teardown | check: **nothing left** |

| Service | Halyx API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| S3 | `BlobStore` | 75 | 74 | 1 | 145.5s |
| DynamoDB | `DocumentStore` | 23 | 23 | 0 | 9.9s |
| DynamoDB | Spring Data repositories | 10 | 10 | 0 | 3.8s |
| SSM Parameter Store | `ParameterStore` | 22 | 21 | 0 | 13.4s |
| Secrets Manager | `ParameterStore` | 22 | 22 | 0 | 48.4s |
| SNS → SQS | `Publisher` / `Subscription` | 15 | 14 | 1 | 79.3s |
| SQS queue | `Publisher` / `Subscription` | 15 | 15 | 0 | 31.2s |
| **Total** | | **182** | **179** | **2** | |
| DynamoDB, Spring Boot 3.5 | Spring Boot repository test | 1 | 1 | | |
| DynamoDB, Spring Boot 4 | Spring Boot repository test | 1 | 1 | | |

Skipped tests, both expected:

- **S3 `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't presign;
  S3 can.
- **SNS → SQS `delayedMessageIsHeldBack`.** SNS has no per-message delay, so the driver doesn't declare
  `DELAYED_DELIVERY`. A plain SQS queue does support it, and that test passes in the SQS suite.

The failure, SSM `watchReportsAddedUpdatedAndRemovedValues`, is a timing one: the watch's second
notification came without the expected update. SSM's listings are eventually consistent, and the test passed
in every earlier run; it stays strict, and the run history tracks it.

The repository kit and the Spring Boot repository test run on the tier's two DynamoDB tables and their
`by-status` global secondary index, through `halyx-spring-data-document`.

### Per-test results

<details>
<summary>S3 — BlobStore: 74/75 passed, 145.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 0.50 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.14 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.16 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.28 |
| `copyMissingSourceThrowsNotFound` | passed | 0.07 |
| `copyPreservesHttpHeaders` | passed | 0.21 |
| `defaultTierIsHot` | passed | 0.13 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 0.58 |
| `deleteAllSpansSeveralProviderBatches` | passed | 18.56 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.21 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.20 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.27 |
| `deleteMissingBlobIsIdempotent` | passed | 0.06 |
| `deleteRemovesBlob` | passed | 0.18 |
| `downloadWritesCompleteFile(Path)` | passed | 3.29 |
| `emptyBlobRoundTrips` | passed | 0.17 |
| `failedWriteKeepsExistingBlob` | passed | 5.76 |
| `failedWriteStoresNothing` | passed | 8.15 |
| `getMissingBlobThrowsNotFound` | passed | 0.08 |
| `getReturnsMetadataWithContent` | passed | 0.13 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.18 |
| `headMissingBlobReturnsEmpty` | passed | 0.11 |
| `headReturnsSizeAndLastModified` | passed | 0.14 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.22 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.21 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.14 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.18 |
| `listAllStreamsEveryBlob` | passed | 0.47 |
| `listEmptyPrefixReturnsNothing` | passed | 0.05 |
| `listFiltersByPrefix` | passed | 0.28 |
| `listPaginatesInKeyOrder` | passed | 0.73 |
| `listSummariesCarrySize` | passed | 0.11 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 0.46 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 0.65 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.10 |
| `openEndedRangeReadsToEnd` | passed | 0.12 |
| `parallelReadReassemblesRangesInOrder` | passed | 3.45 |
| `parallelReadValidatesChecksum` | passed | 5.81 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 0.26 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 6.79 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.31 |
| `presignedUploadUrlUploadsBlob` | passed | 0.27 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 0.34 |
| `presignedUrlReportsExpiry` | passed | 0.00 |
| `putFromFile(Path)` | passed | 0.16 |
| `putFromStream` | passed | 0.14 |
| `putLargeFile(Path)` | passed | 15.82 |
| `putOverwritesExistingBlob` | passed | 0.21 |
| `putReturnsETagMatchingHead` | passed | 0.13 |
| `putSmallUnknownLengthStream` | passed | 0.12 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.14 |
| `putStoresStandardHttpHeaders` | passed | 0.10 |
| `putThenGetRoundTripsContent` | passed | 0.17 |
| `putUnknownLengthStream` | passed | 9.12 |
| `putWithTierStoresTier` | passed | 0.31 |
| `rangeReadReturnsSlice` | passed | 0.18 |
| `setTierChangesTierButNotContent` | passed | 0.30 |
| `setTierToCurrentTierIsANoOp` | passed | 0.39 |
| `storedChecksumsMatchTheContent` | passed | 0.10 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.32 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.06 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.46 |
| `updateMetadataWithStaleETagFails` | passed | 0.33 |
| `validatedFileAndStreamUploads(Path)` | passed | 2.05 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 7.92 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.19 |
| `validatedWriteInParts` | passed | 12.25 |
| `writeEmptyBlob` | passed | 0.13 |
| `writeExactMultipleOfPartSize` | passed | 13.29 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 13.41 |
| `writeLargeBlobInParts` | passed | 6.96 |
| `writeSmallBlobInOneRequest` | passed | 0.20 |

</details>

<details>
<summary>DynamoDB — DocumentStore: 23/23 passed, 9.9s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.34 |
| `concurrentIncrementsAreNotLost` | passed | 0.50 |
| `decimalsRoundTripAsNumbers` | passed | 0.08 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.32 |
| `expiredItemsAreNeverReturned` | passed | 3.44 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 0.52 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.47 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.17 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 0.55 |
| `indexQueryPagesThroughManyMatches` | passed | 0.27 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.20 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.37 |
| `missingItemIsEmpty` | passed | 0.11 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.11 |
| `putReplacesTheWholeItem` | passed | 0.14 |
| `queryPagesThroughLargePartitions` | passed | 0.69 |
| `queryReturnsAPartitionInSortOrder` | passed | 0.39 |
| `transactionAppliesEveryWriteTogether` | passed | 0.42 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.37 |
| `updateSetsRemovesAndIncrements` | passed | 0.13 |
| `versionsGuardConcurrentWrites` | passed | 0.28 |

</details>

<details>
<summary>DynamoDB — Spring Data repositories: 10/10 passed, 3.8s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `indexQueriesAcrossPartitions` | passed | 0.44 |
| `indexWithoutASortAttribute` | passed | 0.21 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.51 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.14 |
| `sortKeyConditions` | passed | 0.45 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 0.86 |
| `streamsReadLazily` | passed | 0.21 |
| `unversionedEntitiesAreSavedInBatchesAndOverwritten` | passed | 0.38 |
| `uuidKeysAndStringIds` | passed | 0.20 |
| `windowsScrollThroughAPartition` | passed | 0.40 |

</details>

<details>
<summary>SSM Parameter Store — ParameterStore: 21/22 passed, 13.4s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.51 |
| `deleteIsIdempotent` | passed | 0.53 |
| `deletedNamesCanBeReused` | passed | 0.66 |
| `earlierVersionsCanBeRead` | passed | 0.42 |
| `getAllDoesNotMatchPartialSegments` | passed | 0.65 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 1.01 |
| `invalidNamesAreRejected` | passed | 0.15 |
| `jsonFieldsCanBeRead` | passed | 0.57 |
| `largeValues` | passed | 0.50 |
| `missingIsEmptyAndRequireFails` | passed | 0.25 |
| `nativeNamesAreUsedAsTheyAre` | passed | 0.63 |
| `plainValuesHaveNoFields` | passed | 0.35 |
| `putReplacesWithANewVersion` | passed | 0.43 |
| `putThenGet` | passed | 0.57 |
| `reportsKindAndProvider` | passed | 0.12 |
| `statisticsCountCacheHits` | passed | 0.46 |
| `storesAreIsolatedByPrefix` | passed | 1.53 |
| `typedReads` | passed | 0.37 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.42 |
| `watchReportsAddedUpdatedAndRemovedValues` | **failed** | 1.54 |
| `watchedChangesInvalidateTheCache` | passed | 1.02 |
| `writesInvalidateTheirOwnCache` | passed | 0.71 |

</details>

<details>
<summary>Secrets Manager — ParameterStore: 22/22 passed, 48.4s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.46 |
| `deleteIsIdempotent` | passed | 0.71 |
| `deletedNamesCanBeReused` | passed | 28.22 |
| `earlierVersionsCanBeRead` | passed | 0.64 |
| `getAllDoesNotMatchPartialSegments` | passed | 2.13 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 2.64 |
| `invalidNamesAreRejected` | passed | 0.09 |
| `jsonFieldsCanBeRead` | passed | 0.68 |
| `largeValues` | passed | 0.35 |
| `missingIsEmptyAndRequireFails` | passed | 0.30 |
| `nativeNamesAreUsedAsTheyAre` | passed | 1.85 |
| `plainValuesHaveNoFields` | passed | 0.34 |
| `putReplacesWithANewVersion` | passed | 0.41 |
| `putThenGet` | passed | 0.47 |
| `reportsKindAndProvider` | passed | 0.06 |
| `statisticsCountCacheHits` | passed | 0.41 |
| `storesAreIsolatedByPrefix` | passed | 0.88 |
| `typedReads` | passed | 0.41 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.36 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 3.76 |
| `watchedChangesInvalidateTheCache` | passed | 2.46 |
| `writesInvalidateTheirOwnCache` | passed | 0.78 |

</details>

<details>
<summary>SNS → SQS — Publisher / Subscription: 14/15 passed, 79.3s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 7.83 |
| `binaryBodiesArriveUnchanged` | passed | 3.96 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 5.08 |
| `delayedMessageIsHeldBack` | skipped | 0.00 |
| `extendedLeaseKeepsMessageHidden` | passed | 7.76 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 8.61 |
| `listenerLimitsConcurrency` | passed | 5.63 |
| `nackedMessageIsRedelivered` | passed | 4.05 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 5.28 |
| `publishAllDeliversEveryMessage` | passed | 6.11 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 3.72 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 8.04 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 3.52 |
| `settlingTwiceIsRejected` | passed | 3.74 |
| `unsettledMessageReturnsAfterItsLease` | passed | 5.93 |

</details>

<details>
<summary>SQS queue — Publisher / Subscription: 15/15 passed, 31.2s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 4.34 |
| `binaryBodiesArriveUnchanged` | passed | 0.33 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 1.05 |
| `delayedMessageIsHeldBack` | passed | 4.30 |
| `extendedLeaseKeepsMessageHidden` | passed | 4.36 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 4.96 |
| `listenerLimitsConcurrency` | passed | 2.00 |
| `nackedMessageIsRedelivered` | passed | 0.43 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 0.71 |
| `publishAllDeliversEveryMessage` | passed | 2.24 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 0.36 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 3.40 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 0.14 |
| `settlingTwiceIsRejected` | passed | 0.27 |
| `unsettledMessageReturnsAfterItsLease` | passed | 2.32 |

</details>

## Tier 2: latest result

```bash
TIER=2 AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
```

Terraform (`infra/aws/tier2`) creates RDS for PostgreSQL 17 and RDS for MySQL 8.4 instances in the default VPC:
- `db.t4g.micro`, IAM database authentication on, no backups, no final snapshot
- reachable only from the test machine's public IPv4 address (a `/32` security group rule)
- a master password generated for the run and passed through the environment only. Terraform's state
  holds it, and teardown deletes the state once destroy succeeds.

`RealAwsSqlIT` signs in as the master user and creates two database users:
- `halyx_iam`, enabled for IAM (`GRANT rds_iam` on PostgreSQL, `AWSAuthenticationPlugin` on MySQL)
- `halyx_password_only`, which is not

| | |
|---|---|
| Date | 2026-10-11, 04:14–04:23 UTC |
| Run id | `r26101115149618` |
| Engines | PostgreSQL 17, MySQL 8.4 |
| Result | **BUILD SUCCESS**: 12 passed, 0 failed |
| Teardown | instances, security group and subnet group destroyed; state deleted; check: **nothing left** |

| Test | PostgreSQL | MySQL |
|---|---|---|
| `loginLatency`: runs first and times each login (see below); a warm token login must take under 3s | passed | passed |
| `passwordLoginRunsQueriesOverTls`: master password login, and the session is encrypted | passed (1.2s) | passed (1.2s) |
| `iamTokenLoginRunsQueriesOverTls`: `SqlAuth.iam("halyx_iam")` runs queries as `halyx_iam` over TLS, and the pool reports healthy | passed (9.4s) | passed (10.2s) |
| `replacedConnectionsLogInAgainWithTokens`: three rounds of filling the pool, then evicting it, with every new connection signing in by token | passed (9.7s) | passed (8.9s) |
| `tokenDoesNotLogInAUserNotEnabledForIam`: a token for `halyx_password_only` is rejected, and the pool reports unhealthy | passed (2.6s) | passed (2.5s) |
| `wrongPasswordDoesNotLogIn` | passed (1.4s) | passed (1.6s) |

TLS is checked on the server side: `pg_stat_ssl` for the session on PostgreSQL, and `Ssl_version` on
MySQL. It isn't inferred from the client settings. The per-test times above come from the earlier run
`r2610111453e0bc`, before `loginLatency` existed. Without it, the first IAM test also paid the
first-login cost described below.

### Login latency

Run `r26101115149618` timed every login on newly created instances, in order. It measured plain JDBC with
no Halyx code in the path, and then a Halyx IAM pool:

| Login | PostgreSQL | MySQL |
|---|---|---|
| plain JDBC, password (×3) | 327–369 ms | 344–368 ms |
| plain JDBC, **first** token on the instance | **6,120 ms** | **5,202 ms** |
| plain JDBC, later tokens (×4) | 650–945 ms | 791–1,078 ms |
| Halyx pool, new token connections (×3) | 569–1,327 ms | 518–1,196 ms |
| token signing, client side | 1 ms (122 ms on first use, when credentials resolve) | 0–3 ms |

Findings:

- **Halyx adds no measurable cost.** Signing is local and takes about 1ms, and the token is cached per
  pool. Pool connections cost the same as plain JDBC with a token.
- **The cost is on the server.** RDS checks each token as the connection logs in. That adds about
  0.3–0.7s per new connection over a password login. The first token login a new instance sees takes
  5–6s.
- **Pools hide the cost.** Only opening a connection pays it. `minIdle > 0` opens connections in the
  background, so the first request doesn't wait.

Earlier, `iamTokenLoginRunsQueriesOverTls` took about 9.4s because it was the first IAM login on the
instance. `replacedConnectionsLogInAgainWithTokens` took about 9s because it opens 12 connections.

## Tier 3: latest result

```bash
TIER=3 AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
```

Terraform (`infra/aws/tier3`) creates the following in the default VPC:
- **Cache:** an ElastiCache for Valkey 8.0 replication group, one `cache.t4g.micro` node, with TLS in
  transit and encryption at rest.
- **Users:** a user group with the required `default` user, switched off. Valkey users can't be
  password-free, so it gets a random password for each run that nothing uses. There are also two IAM
  users:
  - `halyx-<run>-app`, which the caller's policy may connect as
  - `halyx-<run>-denied`, which it may not
- **Bastion:** a `t4g.micro` instance. The cache accepts connections only from the bastion. The bastion
  accepts SSH only from the test machine's `/32`, with a key pair generated for each run.

ElastiCache has no public endpoint, so the script forwards `127.0.0.1:16379` through the bastion. It also
gives the test JVM a hosts file (`-Djdk.net.hosts.file`) that points the cache's real hostname at
127.0.0.1. The client connects to the real name, so TLS checks the real certificate *and* hostname, just
as in production. The caches use `redis.dns-resolver=jdk`, because Lettuce's own DNS client ignores JVM
hosts files (see the findings below).

| | |
|---|---|
| Date | 2026-10-11, 05:28–05:46 UTC (18 min: cache created in 8m 59s, tests 18s, teardown the rest) |
| Run id | `r2610111628619f` |
| Engine | Valkey 8.0 |
| Result | **52 passed, 1 failed** (`iamUserWithoutConnectPermissionIsRejected`, see below) |
| Teardown | replication group, users, user group, subnet group, bastion, key pair and security groups destroyed; state and run key deleted; check: **nothing left** |

| Test | Result | Time |
|---|---|---|
| Cache conformance kit, logged in as `halyx-<run>-app` with IAM tokens over verified TLS | **49/49 passed** | 13.5s |
| `iamUserReadsAndWritesOverVerifiedTls` | passed | 0.90s |
| `unauthenticatedConnectionIsRejected` | passed | 0.20s |
| `tokenSignedForAnotherCacheIsRejected`: a token signed for another replication group id | passed | 2.83s |
| `iamUserWithoutConnectPermissionIsRejected` | **failed**: the `-denied` user connected | 0.33s |

This is the first time ElastiCache IAM authentication has been verified end to end. The emulator can't
attach IAM users to a replication group, so until now tokens had only been checked against a SigV4
validator.

**The open failure.** `halyx-<run>-denied` is a real IAM-enabled user. The test signs the token for that
user and opens its own connection, because connections are keyed by user. ElastiCache accepted it, so the
caller's effective IAM permissions include `elasticache:Connect` on that user. The intended policy, below,
grants it only for `user:halyx-*-app`. The policy actually attached to `halyx_test` still needs checking
for a broader grant. The test stays strict, since a policy that's too broad is exactly what it should
catch.

<details>
<summary>Cache conformance kit: 49/49 passed, 13.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `binaryValuesWithBytesCodec` | passed | 0.12 |
| `clearMatchesNamespaceLiterally` | passed | 0.23 |
| `clearRemovesOnlyThisNamespace` | passed | 0.81 |
| `clearWithoutNamespaceIsRefused` | passed | 0.00 |
| `concurrentGetOrLoadSharesOneLoad` | passed | 0.48 |
| `customCodec` | passed | 0.11 |
| `defaultTtlApplies` | passed | 0.10 |
| `deleteAllCountsExistingKeys` | passed | 0.18 |
| `deleteIfEqualsOnlyDeletesMatchingValue` | passed | 0.23 |
| `deleteReportsWhetherKeyExisted` | passed | 0.20 |
| `emptyBulkOperations` | passed | 0.00 |
| `entriesExpire` | passed | 0.47 |
| `expireOnMissingKeyReturnsFalse` | passed | 0.05 |
| `expireSetsNewTtl` | passed | 0.45 |
| `expiredLeaseCannotReleaseTheNextHoldersLock` | passed | 0.55 |
| `extendKeepsTheLockAlive` | passed | 0.82 |
| `failingLoaderCachesNothing` | passed | 0.26 |
| `getAndDeleteConsumesOnce` | passed | 0.18 |
| `getOrLoadLoadsOnceThenServesFromCache` | passed | 0.20 |
| `getOrLoadWithTtl` | passed | 0.18 |
| `incrementCreatesAndAdds` | passed | 0.25 |
| `incrementKeepsTtl` | passed | 0.20 |
| `incrementOnNonNumberFails` | passed | 0.09 |
| `incrementWithTtlStartsAWindowOnce` | passed | 0.93 |
| `invalidKeysAreRejected` | passed | 0.00 |
| `largeValue` | passed | 2.27 |
| `lockGivesUpAfterWaiting` | passed | 0.35 |
| `lockIsExclusiveUntilReleased` | passed | 0.20 |
| `lockWaitsForTheHolder` | passed | 0.60 |
| `missingKeyIsEmpty` | passed | 0.09 |
| `namespacesAreIsolated` | passed | 0.16 |
| `nonPositiveTtlIsRejected` | passed | 0.00 |
| `nullLoadResultIsNotCached` | passed | 0.17 |
| `putAllAndGetAll` | passed | 0.12 |
| `putAllWithTtl` | passed | 0.20 |
| `putIfAbsentOnlyStoresOnce` | passed | 0.12 |
| `putIfAbsentWithTtlExpires` | passed | 0.42 |
| `putOverwrites` | passed | 0.16 |
| `putThenGet` | passed | 0.09 |
| `replaceIsCompareAndSet` | passed | 0.35 |
| `reportsProviderAndNamespace` | passed | 0.00 |
| `statisticsCountHitsMissesAndLoads` | passed | 0.36 |
| `ttlOfMissingKeyIsEmpty` | passed | 0.05 |
| `ttlReportsRemainingTime` | passed | 0.10 |
| `unicodeKeysAndValues` | passed | 0.09 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateOfMissingKeyStoresNothing` | passed | 0.10 |
| `updateReplacesExistingValueAndKeepsTtl` | passed | 0.19 |
| `withoutTtlEntriesDoNotExpire` | passed | 0.16 |

</details>

## Tier 4: not run

```bash
TIER=4 scripts/aws-real-cloud.sh
```

Tier 4 is ready but hasn't run. The test account is on the AWS Free plan, and the plan's service control
policy denies every `kafka:*` action (`explicit deny in a service control policy`). Only moving the account
to the paid plan lifts it; an IAM policy on the test user can't.

What a run does: Terraform (`infra/aws/tier4`) creates an **MSK Serverless** cluster with IAM access control,
a bucket for claim checks, and a `t4g.small` runner VM in the same VPC that accepts SSH only from the test
machine, with a key pair generated for the run. MSK Serverless has no public endpoint, and Kafka clients
connect to each broker by the name the cluster advertises, so the SSH tunnel used for ElastiCache can't
work. Instead the script builds the tests on the test machine, copies them to the runner with their
classpath (`scripts/in-vpc-runner.sh`), and runs `RealAwsStreamIT` there with the JUnit console launcher.
The runner has no instance profile: it gets two-hour session credentials of the test user
(`sts get-session-token`), sent over SSH and never written to its disk. Then `RealSpringStreamIT` runs there
on Spring Boot 3 and 4, as on [Azure](azure.md#tier-4-latest-result) and [Google Cloud](gcp.md#tier-4-latest-result).
The reports come back into each module's `target/failsafe-reports`.

## Run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r26101113239811` | tier 1 | 148 passed, 22 errors, 2 skipped | clean | Secrets Manager 0/22: the IAM user had no Secrets Manager permissions. This also exposed that `AwsErrors` mapped a 400 `AccessDeniedException` to an invalid argument instead of access denied. |
| `r26101113365837` | tier 1, Secrets Manager | 14/22 | clean | `BatchGetSecretValue` not yet granted. Writes failed while a deleted name was still being deleted, and listings lagged behind writes. |
| `r2610111345e031` | tier 1, Secrets Manager | 19/22 | clean | A read during a pending delete threw. `BatchGetSecretValue` was still denied right after it was granted, probably because IAM changes take time to propagate. |
| `r26101113510091` | tier 1, Secrets Manager | 22/22 | clean | |
| `r26101113562b9e` | tier 1, Secrets Manager | 22/22 | clean | Confirmed the batch read path with the final policy. |
| `r2610111402665b` | tier 1 | 169 passed, 1 failed, 2 skipped | clean | Secrets Manager `nativeNamesAreUsedAsTheyAre`: `get` returned a secret just after its delete was accepted. |
| `r2610111411c824` | tier 1 | 169 passed, 1 failed, 2 skipped | clean | Secrets Manager `watchedChangesInvalidateTheCache`: the watch saw the new value, but the read after it returned the old one. |
| `r2610111422284e` | tier 1 | **170 passed, 2 skipped** | clean | |
| `r26101114370e5e` | tier 1, Secrets Manager | 22/22 | clean | The waits were removed from the driver. Writing a name that is still being deleted now fails with `UnavailableException`, and the kit retries via `deleteLag()`. `deletedNamesCanBeReused` took 18.2s, which is how long AWS took to finish that delete. |
| `r26101114471b0c` | tier 2 | not run | clean (subnet group deleted) | Stopped at `terraform apply`: no `ec2:CreateSecurityGroup` permission. |
| `r2610111453e0bc` | tier 2 | 10/10 | clean | The first IAM login took about 9s, which led to the latency investigation. |
| `r26101115149618` | tier 2 | **12/12** | clean | Latest tier 2 result. `loginLatency` added: the slow first token login also happens with plain JDBC, so the cost is on the RDS side. |
| `r26101115384945` | tier 1, DynamoDB | 23/23 | clean | DynamoDB batches no longer sleep and resend unprocessed items; they throw `ThrottledException`. |
| `r2610111548cee8` | tier 3 | not run | clean | Stopped at `terraform apply`. The account is on the Free Tier plan, which can't launch `t4g.nano` (bastion moved to `t4g.micro`), and Valkey refuses password-free users (the `default` user now gets a random password). |
| `r26101115542175` | tier 3 | 5/53 | clean | Every connection failed: Lettuce resolved the cache's name with Netty's DNS client, which ignores the JVM hosts file, so nothing went through the tunnel. Led to `redis.dns-resolver=jdk`. The bastion showed in the tagging index after termination, so the check now looks at instances and volumes directly. |
| `r2610111628619f` | tier 3 | **52/53** | clean | Latest tier 3 result, above. The IAM user without `Connect` permission was let in. |
| — | tier 4 | not run | nothing created | Checked before a run: with the tier 4 policy attached, `kafka:ListClustersV2` is still denied by the Free plan's service control policy. |
| `r261016123896d3` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | 179 passed, 1 failed, 2 skipped; Spring 2/2 | clean | Latest tier 1 result, above. Repositories 10/10 on DynamoDB. SSM `watchReportsAddedUpdatedAndRemovedValues` failed once: a change notification without the expected update. |

## What real AWS found

Emulators missed all of these:

| Finding | Fix |
|---|---|
| A 400 `AccessDeniedException` (and other auth error codes) was mapped by status to `InvalidArgument` | `AwsErrors` checks auth error codes before status codes, so they map to `AccessDenied` |
| A deleted secret's name can't be reused until AWS finishes the delete ("marked for deletion"; 18s measured) | The write fails with the retryable `UnavailableException`; `delete` treats an in-progress delete as done. Halyx doesn't wait, so callers retry. The TCK allows for this with `deleteLag()` |
| Reading a secret whose delete is still in progress throws | `get` returns empty |
| `GetSecretValue` can return a secret for a moment after its delete is accepted | Documented as eventual. The TCK allows for it with `deleteLag()`. The API doesn't wait |
| `ListSecrets` lags behind writes | Documented as eventual. The Secrets Manager TCK allows a listing lag (`listingLag()`) |
| A listing can see a new value before `GetSecretValue` does | When a watch reports a change, it caches the values it saw, so reads agree with the event on every provider |
| Lettuce resolves host names with Netty's own DNS client, which ignores JVM DNS settings (`jdk.net.hosts.file`, DNS cache TTLs, custom `InetAddressResolver`s) | New opt-in cache property `redis.dns-resolver=jdk` resolves like the rest of the JVM; the default is unchanged |
| Not every policy grants `BatchGetSecretValue` | If batch reads are denied, `getAll` reads secrets one at a time and logs a warning |

## IAM for tier 1

The `halyx_test` user needs the following. Secrets are limited to the `tck-*` names the kits use.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "secretsmanager:ListSecrets", "Resource": "*" },
    { "Effect": "Allow", "Action": "secretsmanager:BatchGetSecretValue", "Resource": "*" },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:CreateSecret", "secretsmanager:PutSecretValue", "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret", "secretsmanager:ListSecretVersionIds", "secretsmanager:DeleteSecret"
      ],
      "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:tck-*"
    }
  ]
}
```

The user also needs:
- S3 and DynamoDB access to create, use and destroy the run's bucket and tables
- SNS, SQS and SSM access for the topics, queues and parameters the tests create
- `tag:GetResources` for the teardown check

## IAM for tier 2

In addition to tier 1:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ec2:CreateSecurityGroup", "ec2:DeleteSecurityGroup",
        "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress",
        "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress",
        "ec2:CreateTags", "ec2:DescribeSecurityGroups", "ec2:DescribeVpcs", "ec2:DescribeSubnets",
        "ec2:DescribeVpcAttribute", "ec2:DescribeNetworkInterfaces", "ec2:DescribeAccountAttributes"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "rds:CreateDBInstance", "rds:DeleteDBInstance", "rds:DescribeDBInstances", "rds:ModifyDBInstance",
        "rds:CreateDBSubnetGroup", "rds:DeleteDBSubnetGroup", "rds:DescribeDBSubnetGroups",
        "rds:AddTagsToResource", "rds:RemoveTagsFromResource", "rds:ListTagsForResource"
      ],
      "Resource": "*"
    },
    { "Effect": "Allow", "Action": "rds-db:connect", "Resource": "arn:aws:rds-db:<region>:<account>:dbuser:*/halyx_iam" },
    {
      "Effect": "Allow",
      "Action": "iam:CreateServiceLinkedRole",
      "Resource": "*",
      "Condition": { "StringEquals": { "iam:AWSServiceName": "rds.amazonaws.com" } }
    }
  ]
}
```

`rds-db:connect` is what the tests exercise. It is granted for `halyx_iam` only, so the token for
`halyx_password_only` is refused. `iam:CreateServiceLinkedRole` is needed only the first time an account
uses RDS.

## IAM for tier 3

A customer-managed policy, not an inline one. Inline policies on a user share a 2,048-character limit, and
tiers 1 and 2 had used most of it. The EC2 security-group permissions come from tier 2.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ec2:RunInstances", "ec2:TerminateInstances", "ec2:DescribeInstances", "ec2:DescribeInstanceAttribute",
        "ec2:DescribeInstanceTypes", "ec2:DescribeInstanceCreditSpecifications", "ec2:DescribeImages",
        "ec2:DescribeTags", "ec2:DescribeVolumes", "ec2:ImportKeyPair", "ec2:DeleteKeyPair", "ec2:DescribeKeyPairs"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "elasticache:CreateReplicationGroup", "elasticache:DeleteReplicationGroup",
        "elasticache:DescribeReplicationGroups", "elasticache:ModifyReplicationGroup",
        "elasticache:DescribeCacheClusters",
        "elasticache:CreateCacheSubnetGroup", "elasticache:DeleteCacheSubnetGroup", "elasticache:DescribeCacheSubnetGroups",
        "elasticache:CreateUser", "elasticache:DeleteUser", "elasticache:ModifyUser", "elasticache:DescribeUsers",
        "elasticache:CreateUserGroup", "elasticache:DeleteUserGroup", "elasticache:ModifyUserGroup", "elasticache:DescribeUserGroups",
        "elasticache:AddTagsToResource", "elasticache:RemoveTagsFromResource", "elasticache:ListTagsForResource"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "elasticache:Connect",
      "Resource": [
        "arn:aws:elasticache:<region>:<account>:replicationgroup:halyx-*",
        "arn:aws:elasticache:<region>:<account>:user:halyx-*-app"
      ]
    },
    {
      "Effect": "Allow",
      "Action": "iam:CreateServiceLinkedRole",
      "Resource": "*",
      "Condition": { "StringEquals": { "iam:AWSServiceName": "elasticache.amazonaws.com" } }
    }
  ]
}
```

## IAM for tier 4

Tier 4 also needs tier 3's EC2 permissions, for the runner, and tier 1's S3 permissions, for the claim-check
bucket.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "kafka:CreateClusterV2", "kafka:DeleteCluster", "kafka:DescribeClusterV2", "kafka:ListClustersV2",
        "kafka:GetBootstrapBrokers", "kafka:TagResource", "kafka:UntagResource", "kafka:ListTagsForResource"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": ["ec2:CreateVpcEndpoint", "ec2:DeleteVpcEndpoints", "ec2:DescribeVpcEndpoints"],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:Connect", "kafka-cluster:DescribeCluster",
        "kafka-cluster:CreateTopic", "kafka-cluster:DeleteTopic", "kafka-cluster:DescribeTopic",
        "kafka-cluster:ReadData", "kafka-cluster:WriteData", "kafka-cluster:WriteDataIdempotently",
        "kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup",
        "kafka-cluster:DescribeTransactionalId", "kafka-cluster:AlterTransactionalId"
      ],
      "Resource": [
        "arn:aws:kafka:<region>:<account>:cluster/halyx-*/*",
        "arn:aws:kafka:<region>:<account>:topic/halyx-*/*",
        "arn:aws:kafka:<region>:<account>:group/halyx-*/*",
        "arn:aws:kafka:<region>:<account>:transactional-id/halyx-*/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": "iam:CreateServiceLinkedRole",
      "Resource": "*",
      "Condition": { "StringEquals": { "iam:AWSServiceName": "kafka.amazonaws.com" } }
    }
  ]
}
```
