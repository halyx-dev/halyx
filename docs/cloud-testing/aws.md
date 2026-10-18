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
| [1](#tier-1-latest-result) | S3, DynamoDB, SSM Parameter Store, Secrets Manager, SNS, SQS, EventBridge; Spring Data repositories (blocking and reactive) on DynamoDB, and Spring Boot on DynamoDB, EventBridge and, with halyx-reactor, WebFlux | **197/199**, 2 skipped (expected); Spring **10/10** |
| [2](#tier-2-latest-result) | RDS for PostgreSQL and MySQL with IAM auth; R2DBC and Spring Data R2DBC | **16/16**; Spring **2/2** |
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

1. **Provision.** Terraform (`infra/aws/tier1`) creates one S3 bucket, two DynamoDB tables, and an EventBridge
   event bus with a rule that delivers the kit's events to an SQS inbox queue. Their names
   include a fresh run id (`r<yyMMddHHmm><hex>`), and everything is tagged `halyx-test=<run id>`.
2. **Test.** `RealAwsIT` runs the conformance kits, and is enabled only by `-Dhalyx.real.aws=true`.
   The tests create SNS topics, SQS queues, SSM parameters and secrets themselves, then delete them.
3. **Tear down.** This runs on exit, failure or Ctrl-C. A sweep deletes anything the tests left behind:
   - SNS topics and SQS queues named `halyx-<run>-*`
   - SSM parameters and secrets under `tck-*` created since the run started
     (secrets are deleted without recovery)

   Then `terraform destroy` runs, and a check confirms that nothing tagged with the run id, and none of
   the bucket, tables, topics, queues, event buses or rules, is left.

Credentials come from the named AWS profile and never from the repository.

## Tier 1: latest result

| | |
|---|---|
| Date | 2026-10-16, 16:16–16:26 UTC (10 min including provision and teardown) |
| Run id | `r2610170316f538` |
| Region | `ap-southeast-2` |
| Toolchain | Java 25.0.2, Maven 3.9.14, Terraform 1.5.7; Spring Boot 3.5.16 and 4.1.1 |
| Result | **197 passed**, 2 skipped (expected); Spring Boot repository, event and reactive tests passed on Boot 3 and 4 |
| Teardown | check: **nothing left** |

| Service | Halyx API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| S3 | `BlobStore` | 75 | 74 | 1 | 159.9s |
| DynamoDB | `DocumentStore` | 23 | 23 | 0 | 10.1s |
| DynamoDB | Spring Data repositories | 10 | 10 | 0 | 4.1s |
| DynamoDB | reactive Spring Data repositories | 10 | 10 | 0 | 3.4s |
| SSM Parameter Store | `ParameterStore` | 22 | 22 | 0 | 13.0s |
| Secrets Manager | `ParameterStore` | 22 | 22 | 0 | 32.5s |
| SNS → SQS | `Publisher` / `Subscription` | 15 | 14 | 1 | 79.0s |
| SQS queue | `Publisher` / `Subscription` | 15 | 15 | 0 | 32.4s |
| EventBridge → SQS | `EventPublisher` / `CloudEvents.decode` | 7 | 7 | 0 | 34.9s |
| **Total** | | **199** | **197** | **2** | |
| DynamoDB, Spring Boot 3.5 | Spring Boot repository test | 1 | 1 | | |
| DynamoDB, Spring Boot 4 | Spring Boot repository test | 1 | 1 | | |
| EventBridge → SQS, Spring Boot 3.5 | Spring Boot event test | 1 | 1 | | |
| EventBridge → SQS, Spring Boot 4 | Spring Boot event test | 1 | 1 | | |
| S3, DynamoDB and EventBridge, Spring Boot 3.5 | Spring Boot reactive test (WebFlux, reactive repository, events) | 3 | 3 | | |
| S3, DynamoDB and EventBridge, Spring Boot 4 | Spring Boot reactive test (WebFlux, reactive repository, events) | 3 | 3 | | |

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
<summary>S3 — BlobStore: 74/75 passed, 159.9s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 0.44 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.12 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.13 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.27 |
| `copyMissingSourceThrowsNotFound` | passed | 0.06 |
| `copyPreservesHttpHeaders` | passed | 0.21 |
| `defaultTierIsHot` | passed | 0.13 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 0.51 |
| `deleteAllSpansSeveralProviderBatches` | passed | 20.39 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.17 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.23 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.40 |
| `deleteMissingBlobIsIdempotent` | passed | 0.06 |
| `deleteRemovesBlob` | passed | 0.18 |
| `downloadWritesCompleteFile(Path)` | passed | 4.34 |
| `emptyBlobRoundTrips` | passed | 0.19 |
| `failedWriteKeepsExistingBlob` | passed | 5.29 |
| `failedWriteStoresNothing` | passed | 7.78 |
| `getMissingBlobThrowsNotFound` | passed | 0.06 |
| `getReturnsMetadataWithContent` | passed | 0.14 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.20 |
| `headMissingBlobReturnsEmpty` | passed | 0.11 |
| `headReturnsSizeAndLastModified` | passed | 0.13 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.21 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.26 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.14 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.22 |
| `listAllStreamsEveryBlob` | passed | 0.56 |
| `listEmptyPrefixReturnsNothing` | passed | 0.06 |
| `listFiltersByPrefix` | passed | 0.27 |
| `listPaginatesInKeyOrder` | passed | 0.71 |
| `listSummariesCarrySize` | passed | 0.13 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 0.51 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 0.55 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.14 |
| `openEndedRangeReadsToEnd` | passed | 0.15 |
| `parallelReadReassemblesRangesInOrder` | passed | 4.89 |
| `parallelReadValidatesChecksum` | passed | 5.78 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 0.32 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 12.68 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.30 |
| `presignedUploadUrlUploadsBlob` | passed | 0.28 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 0.30 |
| `presignedUrlReportsExpiry` | passed | 0.00 |
| `putFromFile(Path)` | passed | 0.16 |
| `putFromStream` | passed | 0.15 |
| `putLargeFile(Path)` | passed | 24.50 |
| `putOverwritesExistingBlob` | passed | 0.17 |
| `putReturnsETagMatchingHead` | passed | 0.14 |
| `putSmallUnknownLengthStream` | passed | 0.13 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.13 |
| `putStoresStandardHttpHeaders` | passed | 0.14 |
| `putThenGetRoundTripsContent` | passed | 0.13 |
| `putUnknownLengthStream` | passed | 9.47 |
| `putWithTierStoresTier` | passed | 0.28 |
| `rangeReadReturnsSlice` | passed | 0.14 |
| `setTierChangesTierButNotContent` | passed | 0.36 |
| `setTierToCurrentTierIsANoOp` | passed | 0.38 |
| `storedChecksumsMatchTheContent` | passed | 0.15 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.32 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.05 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.44 |
| `updateMetadataWithStaleETagFails` | passed | 0.24 |
| `validatedFileAndStreamUploads(Path)` | passed | 1.58 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 8.62 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.18 |
| `validatedWriteInParts` | passed | 13.86 |
| `writeEmptyBlob` | passed | 0.15 |
| `writeExactMultipleOfPartSize` | passed | 12.30 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 6.59 |
| `writeLargeBlobInParts` | passed | 9.00 |
| `writeSmallBlobInOneRequest` | passed | 0.20 |

</details>

<details>
<summary>DynamoDB — DocumentStore: 23/23 passed, 10.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.43 |
| `concurrentIncrementsAreNotLost` | passed | 0.48 |
| `decimalsRoundTripAsNumbers` | passed | 0.10 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.32 |
| `expiredItemsAreNeverReturned` | passed | 3.45 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 0.51 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.47 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.19 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 0.55 |
| `indexQueryPagesThroughManyMatches` | passed | 0.25 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.19 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.40 |
| `missingItemIsEmpty` | passed | 0.10 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.11 |
| `putReplacesTheWholeItem` | passed | 0.15 |
| `queryPagesThroughLargePartitions` | passed | 0.70 |
| `queryReturnsAPartitionInSortOrder` | passed | 0.41 |
| `transactionAppliesEveryWriteTogether` | passed | 0.43 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.38 |
| `updateSetsRemovesAndIncrements` | passed | 0.16 |
| `versionsGuardConcurrentWrites` | passed | 0.28 |

</details>

<details>
<summary>DynamoDB — Spring Data repositories: 10/10 passed, 4.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `indexQueriesAcrossPartitions` | passed | 0.46 |
| `indexWithoutASortAttribute` | passed | 0.23 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.50 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.16 |
| `sortKeyConditions` | passed | 0.47 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 0.84 |
| `streamsReadLazily` | passed | 0.21 |
| `unversionedEntitiesAreSavedInBatchesAndOverwritten` | passed | 0.36 |
| `uuidKeysAndStringIds` | passed | 0.44 |
| `windowsScrollThroughAPartition` | passed | 0.42 |

</details>

<details>
<summary>DynamoDB — reactive Spring Data repositories: 10/10 passed, 3.4s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `fluxQueriesStreamAndCanStopEarly` | passed | 0.21 |
| `indexQueriesAcrossPartitions` | passed | 0.46 |
| `indexWithoutASortAttribute` | passed | 0.20 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.49 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.19 |
| `sortKeyConditions` | passed | 0.45 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 0.41 |
| `unversionedEntitiesAreSavedInBatchesFromIterablesAndPublishers` | passed | 0.41 |
| `uuidKeysAndStringIds` | passed | 0.18 |
| `windowsScrollThroughAPartition` | passed | 0.43 |

</details>

<details>
<summary>SSM Parameter Store — ParameterStore: 22/22 passed, 13.0s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.51 |
| `deleteIsIdempotent` | passed | 0.45 |
| `deletedNamesCanBeReused` | passed | 0.59 |
| `earlierVersionsCanBeRead` | passed | 0.48 |
| `getAllDoesNotMatchPartialSegments` | passed | 0.79 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 0.92 |
| `invalidNamesAreRejected` | passed | 0.12 |
| `jsonFieldsCanBeRead` | passed | 0.50 |
| `largeValues` | passed | 0.41 |
| `missingIsEmptyAndRequireFails` | passed | 0.35 |
| `nativeNamesAreUsedAsTheyAre` | passed | 0.70 |
| `plainValuesHaveNoFields` | passed | 0.38 |
| `putReplacesWithANewVersion` | passed | 0.45 |
| `putThenGet` | passed | 0.52 |
| `reportsKindAndProvider` | passed | 0.16 |
| `statisticsCountCacheHits` | passed | 0.40 |
| `storesAreIsolatedByPrefix` | passed | 0.98 |
| `typedReads` | passed | 0.40 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.43 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 1.85 |
| `watchedChangesInvalidateTheCache` | passed | 1.01 |
| `writesInvalidateTheirOwnCache` | passed | 0.59 |

</details>

<details>
<summary>Secrets Manager — ParameterStore: 22/22 passed, 32.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.59 |
| `deleteIsIdempotent` | passed | 0.93 |
| `deletedNamesCanBeReused` | passed | 11.04 |
| `earlierVersionsCanBeRead` | passed | 0.47 |
| `getAllDoesNotMatchPartialSegments` | passed | 2.16 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 2.68 |
| `invalidNamesAreRejected` | passed | 0.09 |
| `jsonFieldsCanBeRead` | passed | 0.51 |
| `largeValues` | passed | 0.37 |
| `missingIsEmptyAndRequireFails` | passed | 0.35 |
| `nativeNamesAreUsedAsTheyAre` | passed | 1.99 |
| `plainValuesHaveNoFields` | passed | 0.38 |
| `putReplacesWithANewVersion` | passed | 0.58 |
| `putThenGet` | passed | 0.56 |
| `reportsKindAndProvider` | passed | 0.08 |
| `statisticsCountCacheHits` | passed | 0.50 |
| `storesAreIsolatedByPrefix` | passed | 0.80 |
| `typedReads` | passed | 0.42 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.44 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 3.77 |
| `watchedChangesInvalidateTheCache` | passed | 2.60 |
| `writesInvalidateTheirOwnCache` | passed | 1.22 |

</details>

<details>
<summary>SNS → SQS — Publisher / Subscription: 14/15 passed, 79.0s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 7.90 |
| `binaryBodiesArriveUnchanged` | passed | 4.02 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 4.99 |
| `delayedMessageIsHeldBack` | skipped | 0.00 |
| `extendedLeaseKeepsMessageHidden` | passed | 7.99 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 8.46 |
| `listenerLimitsConcurrency` | passed | 5.55 |
| `nackedMessageIsRedelivered` | passed | 4.09 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 5.02 |
| `publishAllDeliversEveryMessage` | passed | 6.31 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 3.75 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 7.84 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 3.58 |
| `settlingTwiceIsRejected` | passed | 3.66 |
| `unsettledMessageReturnsAfterItsLease` | passed | 5.83 |

</details>

<details>
<summary>SQS queue — Publisher / Subscription: 15/15 passed, 32.4s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 4.42 |
| `binaryBodiesArriveUnchanged` | passed | 0.31 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 1.17 |
| `delayedMessageIsHeldBack` | passed | 4.36 |
| `extendedLeaseKeepsMessageHidden` | passed | 4.50 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 4.92 |
| `listenerLimitsConcurrency` | passed | 2.02 |
| `nackedMessageIsRedelivered` | passed | 0.41 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 0.93 |
| `publishAllDeliversEveryMessage` | passed | 2.62 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 0.35 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 3.40 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 0.14 |
| `settlingTwiceIsRejected` | passed | 0.33 |
| `unsettledMessageReturnsAfterItsLease` | passed | 2.50 |

</details>

<details>
<summary>EventBridge → SQS — EventPublisher / CloudEvents.decode: 7/7 passed, 34.9s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `aRepublishedEventKeepsItsId` | passed | 0.37 |
| `eventsWithoutDataArrive` | passed | 0.29 |
| `listenersReceiveEvents` | passed | 0.47 |
| `oversizedEventsAreRefusedBeforeSending` | passed | 0.06 |
| `publishAllDeliversEveryEventAcrossRequests` | passed | 2.16 |
| `publishedEventArrivesWithEveryAttribute` | passed | 1.23 |
| `textAndBinaryData` | passed | 30.28 |

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
| Date | 2026-10-16: PostgreSQL in run `r261016153806f5` (04:38–05:14 UTC), MySQL in run `r261016162844ff` (05:28–05:37 UTC) |
| Run id | `r261016153806f5` and `r261016162844ff` |
| Engines | PostgreSQL 17, MySQL 8.4 |
| Result | 16 passed, 0 failed (JDBC 12, R2DBC 4), across the two runs; Spring Boot R2DBC test passed on Boot 3 and 4 |
| Teardown | instances, security group and subnet group destroyed; state deleted; check: **nothing left** after both runs |

| Test | PostgreSQL | MySQL |
|---|---|---|
| `loginLatency`: runs first and times each login (see below); a warm token login must take under 3s | passed (13.8s) | passed (17.4s) |
| `passwordLoginRunsQueriesOverTls`: master password login, and the session is encrypted | passed (0.9s) | passed (1.4s) |
| `iamTokenLoginRunsQueriesOverTls`: `SqlAuth.iam("halyx_iam")` runs queries as `halyx_iam` over TLS, and the pool reports healthy | passed (1.7s) | passed (1.5s) |
| `replacedConnectionsLogInAgainWithTokens`: three rounds of filling the pool, then evicting it, with every new connection signing in by token | passed (10.2s) | passed (10.5s) |
| `tokenDoesNotLogInAUserNotEnabledForIam`: a token for `halyx_password_only` is rejected, and the pool reports unhealthy | passed (2.6s) | passed (2.5s) |
| `wrongPasswordDoesNotLogIn` | passed (1.3s) | passed (1.3s) |
| `r2dbcIamLoginRunsQueriesOverTls`: R2DBC (`halyx-r2dbc`), `SqlAuth.iam("halyx_iam")`: runs as `halyx_iam`, over TLS | passed (0.9s) | passed (0.9s) |
| `r2dbcTokenDoesNotLogInAUserNotEnabledForIam`: R2DBC: a token for `halyx_password_only` is refused | passed (0.8s) | passed (1.7s) |
| Spring Data R2DBC over `halyx.r2dbc-databases` with RDS IAM, Spring Boot 3.5 and 4 | passed, passed | — |

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
| `r26101115149618` | tier 2 | **12/12** | clean | Login latency above is from this run. `loginLatency` added: the slow first token login also happens with plain JDBC, so the cost is on the RDS side. |
| `r26101115384945` | tier 1, DynamoDB | 23/23 | clean | DynamoDB batches no longer sleep and resend unprocessed items; they throw `ThrottledException`. |
| `r2610111548cee8` | tier 3 | not run | clean | Stopped at `terraform apply`. The account is on the Free Tier plan, which can't launch `t4g.nano` (bastion moved to `t4g.micro`), and Valkey refuses password-free users (the `default` user now gets a random password). |
| `r26101115542175` | tier 3 | 5/53 | clean | Every connection failed: Lettuce resolved the cache's name with Netty's DNS client, which ignores the JVM hosts file, so nothing went through the tunnel. Led to `redis.dns-resolver=jdk`. The bastion showed in the tagging index after termination, so the check now looks at instances and volumes directly. |
| `r2610111628619f` | tier 3 | **52/53** | clean | Latest tier 3 result, above. The IAM user without `Connect` permission was let in. |
| — | tier 4 | not run | nothing created | Checked before a run: with the tier 4 policy attached, `kafka:ListClustersV2` is still denied by the Free plan's service control policy. |
| `r261016123896d3` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | 179 passed, 1 failed, 2 skipped; Spring 2/2 | clean | Repositories 10/10 on DynamoDB. SSM `watchReportsAddedUpdatedAndRemovedValues` failed once: a change notification without the expected update. |
| `r26101614545224` | tier 2, plus R2DBC and Spring Boot 3 and 4 | 8 passed, 8 errors; Spring 0/2 | clean | Every IAM login refused, over JDBC and R2DBC alike: the test user's `rds-db:connect` statement still named the database user from before the rename (`cirrus_iam`). Fixed in the policy. |
| `r261016153806f5` | tier 2, plus R2DBC and Spring Boot 3 and 4 | PostgreSQL **8/8**; MySQL 4 passed, 4 errors; Spring **2/2** | clean | Latest tier 2 PostgreSQL result, above. PostgreSQL's IAM logins passed, over JDBC and R2DBC, and so did Spring Data R2DBC. MySQL still refused `halyx_iam`'s tokens, over JDBC too; its instance took 26m 45s to create. Not explained. |
| `r261016162844ff` | tier 2, MySQL only | **8/8** | clean | MySQL tests only (`IT_TEST='RealAwsSqlIT$MySql'`): every login passed, on an instance ready in 5m 5s. Latest tier 2 MySQL result, above. |
| `r2610161931a2f8` | tier 1, plus the event bus kit and Spring Boot 3 and 4 | **186 passed, 2 skipped**; Spring 4/4 | clean | EventBridge 6/6 through a rule to SQS. The Boot 4 event test took 32s against 2.5s on Boot 3, likely because the inbox queue is shared: SQS can hand messages to a long poll the Boot 3 JVM abandoned when it exited, and they stay hidden for the 30s visibility timeout. |
| `r26101622397240` | tier 1, EventBridge only (`IT_TEST='RealAwsIT$EventBridge'`) | **7/7** | clean | With the kit's data test split in two. |
| `r2610170203d8cc` | tier 1, plus halyx-reactor and Spring Boot 3 and 4 | 196 passed, 1 failed, 2 skipped; Spring 4/10 | clean | Reactive repositories 10/10 on DynamoDB. The reactive Spring test failed to start on both Boots: the test gave its document store and event bus the same key, `orders`, so their beans clashed. SSM `watchReportsAddedUpdatedAndRemovedValues` failed again (see the roadmap). |
| `r2610170316f538` | tier 1, plus halyx-reactor and Spring Boot 3 and 4 | **197 passed, 2 skipped**; Spring **10/10** | clean | Latest tier 1 result, above. The reactive Spring test (WebFlux over the bucket, a reactive repository, events through the reactive beans) 3/3 on Boot 3.5 and 4. |
| `r261017114166f2` | tier 1, plus Spring Boot 3 and 4 | **201 passed, 2 skipped**; Spring 14/16 | clean | First run of the new Spring tests: `@HalyxListener` with a claim check (`RealSpringMessagingIT`) and `spring.config.import` with reload, health ping and the store bean's cache (`RealSpringParametersIT`). Secrets Manager refused the parameters test's writes: its names began `halyx-spring-`, outside the `tck-*` names the IAM policy allows. The test now uses `tck-spring-`. |
| `r2610171157f534` | tier 2, plus Spring Boot 3 and 4 | **16/16**; Spring **2/2** | clean | |
| `r26101712087b06` | tier 3, plus Spring Boot 3 and 4 | 53/54; Spring 0/2 | clean | The IAM user without `Connect` permission was still let in, as before. The new Spring cache test's own `@Bean` was named like its `halyx.caches` bean; renamed. |
| `r26101715276919` | tier 1, plus Spring Boot 3 and 4 | **201 passed, 2 skipped**; Spring **16/16** | clean | Latest tier 1 result: events, reactive (3), repository, messaging and parameters (SSM and Secrets Manager) on Boot 3.5 and 4. |
| `r261017153996fd` | tier 3, plus Spring Boot 3 and 4 | 53/54; Spring **2/2** | clean | Latest tier 3 result. `Cache` beans with IAM, a JSON codec and `@Cacheable` on Boot 3.5 and 4. The IAM user without `Connect` permission was still let in: the test IAM policy still needs checking. |
| `r26101720233d49` | tier 1, plus Spring Boot 3 and 4 | **215 passed, 2 skipped**; Spring **16/16** | clean | Latest tier 1 result, with the asynchronous views (blob, document, messaging and event kits) on every service, non-blocking where the driver has a path: S3, DynamoDB, SNS, SQS and EventBridge. |
| `r2610172237815b` | tier 1, plus Spring Boot 3 and 4 | **221 passed, 2 skipped**; Spring **16/16** | clean | Latest tier 1 result, with asynchronous receives and settlement in the messaging kit, non-blocking on SQS. |
| `r26101814315472` | tier 1, plus Spring Boot 3 and 4 | **227 passed, 1 skipped**; Spring **16/16** | clean | Latest tier 1 result, with the asynchronous parameter store: SSM and Secrets Manager non-blocking, proven with a counting executor, and the Spring parameter beans' async view. |

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
- SNS, SQS and SSM access for the topics, queues and parameters the tests create, including
  `sqs:SetQueueAttributes` for the event inbox's queue policy
- EventBridge for the run's event bus and its rule to the inbox: `events:CreateEventBus`, `DeleteEventBus`,
  `DescribeEventBus`, `PutRule`, `DeleteRule`, `DescribeRule`, `PutTargets`, `RemoveTargets`,
  `ListTargetsByRule`, `TagResource`, `ListTagsForResource`, `PutEvents`, and `ListEventBuses` and `ListRules`
  for the teardown check, on `arn:aws:events:<region>:<account>:event-bus/halyx-*` and `rule/halyx-*/*`
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
