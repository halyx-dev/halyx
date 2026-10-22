---
title: GCP
parent: Cloud testing
nav_order: 2
---

# Google Cloud: real-cloud results
{: .no_toc }

Results of running the Halyx conformance kits against real Google Cloud services in `australia-southeast1` (Sydney), acting as
the `halyx-test` service account, impersonated through Application Default Credentials. Every run creates its own throwaway resources and deletes them afterwards. See
[Cloud testing](index.md) for how the runs work.

| Tier | Services | Latest result |
|---|---|---|
| [1](#tier-1-latest-result) | Cloud Storage, Firestore, Pub/Sub, Secret Manager, Parameter Manager, Eventarc Advanced; Spring Data repositories (blocking and reactive) on Firestore, and Spring Boot on Firestore, Eventarc and, with halyx-reactor, WebFlux | **230/234** passed, 4 skipped (expected); Spring **16/16** |
| [2](#tier-2-latest-result) | Cloud SQL for PostgreSQL and MySQL with IAM auth, direct and through the Cloud SQL connector; R2DBC and Spring Data R2DBC | **22/22**; Spring **2/2** |
| [3](#tier-3-latest-result) | Memorystore for Valkey with IAM auth and TLS (private CA) | **57/57**; Spring **2/2** |
| [4](#tier-4-latest-result) | Streams on Managed Service for Apache Kafka with Google IAM, from a runner inside the VPC, plus Spring Boot 3 and 4 | **29/29**; Spring **2/2** |

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Tier 1: latest result

```bash
GCP_PROJECT=<project> GCP_REGION=australia-southeast1 cloud-testing/scripts/gcp-real-cloud.sh
IT_TEST='RealGcpIT$Firestore' cloud-testing/scripts/gcp-real-cloud.sh     # one suite only
```

**Identity.** Terraform and the tests act as a dedicated `halyx-test` service account, through
Application Default Credentials that impersonate it:

```bash
gcloud auth application-default login --impersonate-service-account=halyx-test@<project>.iam.gserviceaccount.com
```

**Provision.** Terraform (`cloud-testing/infra/gcp/tier1`) creates the following, labelled `halyx-test=<run id>`:
- a Cloud Storage bucket, which also holds the messaging kit's claim-check payloads
- a Firestore database of the run's own, so the project's `(default)` database is never touched
- the composite indexes the document store's index queries need: `status` with `placedAt` ascending and
  descending, on both test tables, plus a collection-group index on `owner`
- an Eventarc Advanced message bus, an enrollment matching every event, and a pipeline that publishes them to
  a Pub/Sub topic with an OAuth token for `halyx-test`, and the topic's inbox subscription

**Test.** `RealGcpIT` creates Pub/Sub topics and subscriptions named after the run, plus `tck-*` secrets
and parameters (Parameter Manager both global and regional).

**Tear down.** A sweep deletes what the tests left behind, then `terraform destroy` runs, then a check
lists every kind of resource. A listing that fails is reported as `CHECK FAILED`, never taken as empty.

| | |
|---|---|
| Date | 2026-10-16, 15:14–15:53 UTC (39 min: the Eventarc pipeline and Firestore indexes about 7 min, tests about 22 min, teardown the rest) |
| Run id | `r2610170214c74e` |
| Project and region | dedicated test project, `australia-southeast1` |
| Result | **BUILD SUCCESS**: 202 passed, 0 failed, 4 skipped (expected); Spring Boot repository, event and reactive tests passed on Boot 3 and 4 |
| Teardown | bucket, Firestore database and Eventarc bus, pipeline and enrollment destroyed; check: **nothing left** |

| Service | Halyx API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| Cloud Storage | `BlobStore` | 75 | 74 | 1 | 520.5s |
| Firestore | `DocumentStore` | 23 | 23 | 0 | 15.3s |
| Firestore | Spring Data repositories | 10 | 10 | 0 | 5.6s |
| Firestore | reactive Spring Data repositories | 10 | 10 | 0 | 4.9s |
| Pub/Sub | `Publisher` / `Subscription` | 15 | 13 | 2 | 250.8s |
| Secret Manager | `ParameterStore` | 22 | 22 | 0 | 164.0s |
| Parameter Manager, global | `ParameterStore` | 22 | 22 | 0 | 127.1s |
| Parameter Manager, `australia-southeast1` | `ParameterStore` | 22 | 22 | 0 | 38.6s |
| Eventarc Advanced → Pub/Sub | `EventPublisher` / `CloudEvents.decode` | 7 | 6 | 1 | 63.5s |
| **Total** | | **206** | **202** | **4** | |
| Firestore, Spring Boot 3.5 | Spring Boot repository test | 1 | 1 | | |
| Firestore, Spring Boot 4 | Spring Boot repository test | 1 | 1 | | |
| Eventarc → Pub/Sub, Spring Boot 3.5 | Spring Boot event test | 1 | 1 | | |
| Eventarc → Pub/Sub, Spring Boot 4 | Spring Boot event test | 1 | 1 | | |
| Bucket, Firestore and Eventarc, Spring Boot 3.5 | Spring Boot reactive test (WebFlux, reactive repository, events) | 3 | 3 | | |
| Bucket, Firestore and Eventarc, Spring Boot 4 | Spring Boot reactive test (WebFlux, reactive repository, events) | 3 | 3 | | |

Skipped tests, all expected:

- **Cloud Storage `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't
  presign; GCS can.
- **Pub/Sub `repeatedDeduplicationIdIsDeliveredOnce` and `delayedMessageIsHeldBack`.** Pub/Sub has no
  publish-side deduplication and no per-message delay, so the driver declares neither capability.
- **Eventarc `eventsWithoutDataArrive`.** Pub/Sub refuses a pipeline's publish of an event without data, so
  the event never arrives; declared as a known gap (see what real GCP found).

Pub/Sub's time is mostly the redelivery tests, which wait for the 10s ack deadline to pass.

The repository kit and the Spring Boot repository test run on the run's `items` and `items-sorted`
collections and their `status`/`placedAt` composite indexes, through `halyx-spring-data-document`.

### Per-test results

<details>
<summary>Cloud Storage — BlobStore: 74/75 passed, 520.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 1.95 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 1.26 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 1.72 |
| `copyDuplicatesContentAndKeepsSource` | passed | 3.03 |
| `copyMissingSourceThrowsNotFound` | passed | 0.29 |
| `copyPreservesHttpHeaders` | passed | 1.20 |
| `defaultTierIsHot` | passed | 0.93 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 5.23 |
| `deleteAllSpansSeveralProviderBatches` | passed | 176.14 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 1.68 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 1.27 |
| `deleteIfMatchWithStaleETagFails` | passed | 2.32 |
| `deleteMissingBlobIsIdempotent` | passed | 0.29 |
| `deleteRemovesBlob` | passed | 1.25 |
| `downloadWritesCompleteFile(Path)` | passed | 8.90 |
| `emptyBlobRoundTrips` | passed | 1.50 |
| `failedWriteKeepsExistingBlob` | passed | 1.53 |
| `failedWriteStoresNothing` | passed | 0.60 |
| `getMissingBlobThrowsNotFound` | passed | 0.29 |
| `getReturnsMetadataWithContent` | passed | 1.21 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 1.65 |
| `headMissingBlobReturnsEmpty` | passed | 0.64 |
| `headReturnsSizeAndLastModified` | passed | 0.92 |
| `ifMatchWithCurrentETagSucceeds` | passed | 1.85 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 3.47 |
| `ifNotExistsCreatesMissingBlob` | passed | 1.56 |
| `ifNotExistsRejectsExistingBlob` | passed | 1.83 |
| `listAllStreamsEveryBlob` | passed | 4.72 |
| `listEmptyPrefixReturnsNothing` | passed | 0.30 |
| `listFiltersByPrefix` | passed | 2.16 |
| `listPaginatesInKeyOrder` | passed | 7.23 |
| `listSummariesCarrySize` | passed | 0.91 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 3.72 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 4.65 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.96 |
| `openEndedRangeReadsToEnd` | passed | 1.27 |
| `parallelReadReassemblesRangesInOrder` | passed | 8.41 |
| `parallelReadValidatesChecksum` | passed | 7.74 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 1.62 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 13.45 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 2.25 |
| `presignedUploadUrlUploadsBlob` | passed | 1.44 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 1.10 |
| `presignedUrlReportsExpiry` | passed | 0.22 |
| `putFromFile(Path)` | passed | 1.17 |
| `putFromStream` | passed | 1.23 |
| `putLargeFile(Path)` | passed | 33.63 |
| `putOverwritesExistingBlob` | passed | 1.87 |
| `putReturnsETagMatchingHead` | passed | 0.93 |
| `putSmallUnknownLengthStream` | passed | 1.21 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.91 |
| `putStoresStandardHttpHeaders` | passed | 0.95 |
| `putThenGetRoundTripsContent` | passed | 1.21 |
| `putUnknownLengthStream` | passed | 33.64 |
| `putWithTierStoresTier` | passed | 1.80 |
| `rangeReadReturnsSlice` | passed | 1.69 |
| `setTierChangesTierButNotContent` | passed | 3.21 |
| `setTierToCurrentTierIsANoOp` | passed | 3.11 |
| `storedChecksumsMatchTheContent` | passed | 0.94 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 1.53 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.28 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 2.39 |
| `updateMetadataWithStaleETagFails` | passed | 1.83 |
| `validatedFileAndStreamUploads(Path)` | passed | 4.55 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 20.70 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 1.48 |
| `validatedWriteInParts` | passed | 28.28 |
| `writeEmptyBlob` | passed | 1.22 |
| `writeExactMultipleOfPartSize` | passed | 31.27 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 23.56 |
| `writeLargeBlobInParts` | passed | 27.14 |
| `writeSmallBlobInOneRequest` | passed | 2.09 |

</details>

<details>
<summary>Firestore — DocumentStore: 23/23 passed, 15.3s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.61 |
| `concurrentIncrementsAreNotLost` | passed | 0.87 |
| `decimalsRoundTripAsNumbers` | passed | 0.10 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.58 |
| `expiredItemsAreNeverReturned` | passed | 3.78 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 0.92 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.31 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.23 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 2.92 |
| `indexQueryPagesThroughManyMatches` | passed | 0.38 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.25 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.49 |
| `missingItemIsEmpty` | passed | 0.15 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.15 |
| `putReplacesTheWholeItem` | passed | 0.16 |
| `queryPagesThroughLargePartitions` | passed | 0.79 |
| `queryReturnsAPartitionInSortOrder` | passed | 0.44 |
| `transactionAppliesEveryWriteTogether` | passed | 0.76 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.43 |
| `updateSetsRemovesAndIncrements` | passed | 0.20 |
| `versionsGuardConcurrentWrites` | passed | 0.77 |

</details>

<details>
<summary>Firestore — Spring Data repositories: 10/10 passed, 5.6s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `indexQueriesAcrossPartitions` | passed | 0.60 |
| `indexWithoutASortAttribute` | passed | 0.30 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.73 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.20 |
| `sortKeyConditions` | passed | 0.62 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 1.47 |
| `streamsReadLazily` | passed | 0.26 |
| `unversionedEntitiesAreSavedInBatchesAndOverwritten` | passed | 0.65 |
| `uuidKeysAndStringIds` | passed | 0.26 |
| `windowsScrollThroughAPartition` | passed | 0.54 |

</details>

<details>
<summary>Firestore — reactive Spring Data repositories: 10/10 passed, 4.9s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `fluxQueriesStreamAndCanStopEarly` | passed | 0.24 |
| `indexQueriesAcrossPartitions` | passed | 0.52 |
| `indexWithoutASortAttribute` | passed | 0.26 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.60 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.24 |
| `sortKeyConditions` | passed | 0.58 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 0.69 |
| `unversionedEntitiesAreSavedInBatchesFromIterablesAndPublishers` | passed | 0.61 |
| `uuidKeysAndStringIds` | passed | 0.22 |
| `windowsScrollThroughAPartition` | passed | 0.96 |

</details>

<details>
<summary>Pub/Sub — Publisher / Subscription: 13/15 passed, 250.8s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 25.61 |
| `binaryBodiesArriveUnchanged` | passed | 12.49 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 13.57 |
| `delayedMessageIsHeldBack` | skipped | 0.00 |
| `extendedLeaseKeepsMessageHidden` | passed | 31.93 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 34.49 |
| `listenerLimitsConcurrency` | passed | 22.06 |
| `nackedMessageIsRedelivered` | passed | 13.64 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 20.60 |
| `publishAllDeliversEveryMessage` | passed | 20.00 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 12.04 |
| `repeatedDeduplicationIdIsDeliveredOnce` | skipped | 0.00 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 8.37 |
| `settlingTwiceIsRejected` | passed | 12.49 |
| `unsettledMessageReturnsAfterItsLease` | passed | 23.49 |

</details>

<details>
<summary>Secret Manager — ParameterStore: 22/22 passed, 164.0s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 7.55 |
| `deleteIsIdempotent` | passed | 7.20 |
| `deletedNamesCanBeReused` | passed | 10.64 |
| `earlierVersionsCanBeRead` | passed | 6.92 |
| `getAllDoesNotMatchPartialSegments` | passed | 12.17 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 18.02 |
| `invalidNamesAreRejected` | passed | 0.30 |
| `jsonFieldsCanBeRead` | passed | 7.08 |
| `largeValues` | passed | 5.75 |
| `missingIsEmptyAndRequireFails` | passed | 1.20 |
| `nativeNamesAreUsedAsTheyAre` | passed | 7.40 |
| `plainValuesHaveNoFields` | passed | 5.88 |
| `putReplacesWithANewVersion` | passed | 7.39 |
| `putThenGet` | passed | 5.59 |
| `reportsKindAndProvider` | passed | 0.31 |
| `statisticsCountCacheHits` | passed | 5.82 |
| `storesAreIsolatedByPrefix` | passed | 9.11 |
| `typedReads` | passed | 5.77 |
| `valuesKeepSpecialCharactersAndLines` | passed | 6.97 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 14.03 |
| `watchedChangesInvalidateTheCache` | passed | 9.02 |
| `writesInvalidateTheirOwnCache` | passed | 9.91 |

</details>

<details>
<summary>Parameter Manager, global — ParameterStore: 22/22 passed, 127.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 6.18 |
| `deleteIsIdempotent` | passed | 3.62 |
| `deletedNamesCanBeReused` | passed | 7.27 |
| `earlierVersionsCanBeRead` | passed | 5.74 |
| `getAllDoesNotMatchPartialSegments` | passed | 9.84 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 14.48 |
| `invalidNamesAreRejected` | passed | 0.25 |
| `jsonFieldsCanBeRead` | passed | 6.06 |
| `largeValues` | passed | 4.40 |
| `missingIsEmptyAndRequireFails` | passed | 0.74 |
| `nativeNamesAreUsedAsTheyAre` | passed | 5.61 |
| `plainValuesHaveNoFields` | passed | 4.27 |
| `putReplacesWithANewVersion` | passed | 5.75 |
| `putThenGet` | passed | 5.22 |
| `reportsKindAndProvider` | passed | 0.24 |
| `statisticsCountCacheHits` | passed | 4.30 |
| `storesAreIsolatedByPrefix` | passed | 6.46 |
| `typedReads` | passed | 4.68 |
| `valuesKeepSpecialCharactersAndLines` | passed | 4.78 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 12.41 |
| `watchedChangesInvalidateTheCache` | passed | 7.98 |
| `writesInvalidateTheirOwnCache` | passed | 6.82 |

</details>

<details>
<summary>Parameter Manager, `australia-southeast1` — ParameterStore: 22/22 passed, 38.6s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 1.75 |
| `deleteIsIdempotent` | passed | 1.16 |
| `deletedNamesCanBeReused` | passed | 2.25 |
| `earlierVersionsCanBeRead` | passed | 1.62 |
| `getAllDoesNotMatchPartialSegments` | passed | 2.72 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 4.45 |
| `invalidNamesAreRejected` | passed | 0.06 |
| `jsonFieldsCanBeRead` | passed | 1.70 |
| `largeValues` | passed | 1.51 |
| `missingIsEmptyAndRequireFails` | passed | 0.20 |
| `nativeNamesAreUsedAsTheyAre` | passed | 1.51 |
| `plainValuesHaveNoFields` | passed | 1.38 |
| `putReplacesWithANewVersion` | passed | 1.53 |
| `putThenGet` | passed | 1.48 |
| `reportsKindAndProvider` | passed | 0.06 |
| `statisticsCountCacheHits` | passed | 1.31 |
| `storesAreIsolatedByPrefix` | passed | 2.54 |
| `typedReads` | passed | 1.47 |
| `valuesKeepSpecialCharactersAndLines` | passed | 1.35 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 3.96 |
| `watchedChangesInvalidateTheCache` | passed | 2.42 |
| `writesInvalidateTheirOwnCache` | passed | 2.15 |

</details>

<details>
<summary>Eventarc Advanced → Pub/Sub — EventPublisher / CloudEvents.decode: 6/7 passed, 63.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `aRepublishedEventKeepsItsId` | passed | 3.07 |
| `eventsWithoutDataArrive` | skipped | 0.00 |
| `listenersReceiveEvents` | passed | 19.80 |
| `oversizedEventsAreRefusedBeforeSending` | passed | 0.06 |
| `publishAllDeliversEveryEventAcrossRequests` | passed | 30.39 |
| `publishedEventArrivesWithEveryAttribute` | passed | 7.64 |
| `textAndBinaryData` | passed | 2.59 |

</details>

## Tier 2: latest result

```bash
TIER=2 GCP_PROJECT=<project> GCP_REGION=australia-southeast1 cloud-testing/scripts/gcp-real-cloud.sh
```

Terraform (`cloud-testing/infra/gcp/tier2`) creates Cloud SQL for PostgreSQL 17 and for MySQL 8.4:
- shared-core `db-f1-micro` on the `ENTERPRISE` edition (newer versions default to Enterprise Plus, which
  has no shared-core tiers)
- IAM authentication on, encrypted connections only, no backups, no deletion protection
- a public IP that accepts only the test machine's `/32`; the Cloud SQL Java connector needs no
  authorized network

Each instance has three users:
- `halyx_admin`, a built-in user with a password generated for the run
- the test service account as an IAM user: `halyx-test@<project>.iam` on PostgreSQL and `halyx-test`
  on MySQL
- `halyx_password_only`, a built-in user that an IAM token must not log in

On MySQL the test then grants the IAM user `SELECT` on the `app` database, as the admin. IAM users start
with no privileges there; see the findings below.

| | |
|---|---|
| Date | 2026-10-16, 04:18–04:36 UTC (18 min: PostgreSQL ready in 12m 13s, MySQL in 12m 43s) |
| Run id | `r2610161518d9f9` |
| Engines | PostgreSQL 17, MySQL 8.4 |
| Result | **BUILD SUCCESS**: 22 passed, 0 failed (JDBC 18, R2DBC 4); Spring Boot R2DBC test passed on Boot 3 and 4 |
| Teardown | instances (with their databases and users) destroyed; state deleted; check: **nothing left** |

| Test | PostgreSQL | MySQL |
|---|---|---|
| `loginLatency`: runs first and times each login (see below); a warm direct-token login must take under 3s | passed (16.6s) | passed (7.0s) |
| `passwordLoginRunsQueriesOverTls`: direct, and the server reports the session encrypted | passed (0.9s) | passed (1.0s) |
| `iamLoginWithDerivedUserRunsQueriesOverTls`: `SqlAuth.iam()` derives the user from the service account | passed (1.4s) | passed (1.4s) |
| `iamLoginWithExplicitUser`: `SqlAuth.iam("<user>")` | passed (1.3s) | passed (1.3s) |
| `connectorIamLogin`: `gcp.sql.instance` through the Cloud SQL Java connector, no host or authorized network | passed (1.0s) | passed (1.0s) |
| `connectorPasswordLogin` | passed (1.3s) | passed (1.2s) |
| `replacedConnectionsLogInAgainWithTokens`: three rounds of filling the pool, then evicting it | passed (6.9s) | passed (7.3s) |
| `tokenDoesNotLogInAUserNotEnabledForIam`: a token for `halyx_password_only` is rejected | passed (3.1s) | passed (3.1s) |
| `wrongPasswordDoesNotLogIn` | passed (1.3s) | passed (1.3s) |
| `r2dbcIamLoginWithDerivedUserRunsQueriesOverTls`: R2DBC (`halyx-r2dbc`), `SqlAuth.iam()` with the user derived from the service account, over TLS | passed (1.4s) | passed (1.4s) |
| `r2dbcThroughTheConnectorIsRefused`: R2DBC needs a host: `gcp.sql.instance` is refused, saying so | passed (0.0s) | passed (0.0s) |
| Spring Data R2DBC over `halyx.r2dbc-databases` with Cloud SQL IAM, Spring Boot 3.5 and 4 | passed, passed | — |

### Login latency

From run `r2610111859cad1`, the last one that timed logins.

Times per new connection, in the order they ran, on newly created instances:

| Login | PostgreSQL | MySQL |
|---|---|---|
| direct, password | 841 ms, then 332–339 ms | 4,515 ms, then 1,184–1,501 ms |
| direct, token | 1,426 ms, then 471–472 ms | 11,402 ms, then 1,538–2,299 ms |
| connector, token | 1,454 ms, then 354–368 ms | 8,700 ms, then 1,706–2,065 ms |

- **PostgreSQL:** a token login costs about 140 ms more than a password login. There's no slow first
  login like RDS's 5–6s.
- **MySQL on `db-f1-micro`:** slow for *every* login, passwords included. The shared-core instance is the
  bottleneck, not IAM or Halyx, and a larger tier would bring it close to PostgreSQL. Its warm token
  logins (up to 2.3s) are near the test's 3s limit, so that limit could become flaky on this tier.
- **The connector** costs the same as a direct connection once warm.

## Tier 3: latest result

```bash
TIER=3 GCP_PROJECT=<project> GCP_REGION=australia-southeast1 cloud-testing/scripts/gcp-real-cloud.sh
```

Terraform (`cloud-testing/infra/gcp/tier3`) creates the following, in a VPC and subnet of the run's own:
- **Cache:** a Memorystore for Valkey 8.0 instance: cluster mode disabled, `SHARED_CORE_NANO`, IAM
  authorization, and TLS (`SERVER_AUTHENTICATION`) with a server certificate from Memorystore's private CA.
  Cluster mode is off so the client gets one endpoint and no redirects to node IPs a tunnel can't reach.
- **Private endpoint:** a service-connection policy (`gcp-memorystore`), under which Memorystore creates
  its Private Service Connect endpoint in the subnet.
- **Bastion:** an `e2-micro` VM with no service account. A firewall rule allows SSH only from the test
  machine's `/32`, with a key generated for each run.

The script then:
1. fetches the instance's CA (`gcloud memorystore instances get-certificate-authority`)
2. forwards `127.0.0.1:16380` through the bastion to the private endpoint
3. runs `RealGcpCacheIT`

The certificate names the private endpoint, not the tunnel's 127.0.0.1, so the caches use
`redis.verify-peer=ca`. That checks the chain against the instance's CA, but not the name.

| | |
|---|---|
| Date | 2026-10-11, 08:38–09:07 UTC (29 min: Memorystore ready in 7m 6s, tests 17s, teardown about 20 min) |
| Run id | `r261011193814e3` |
| Engine | Valkey 8.0, cluster mode disabled |
| Result | **BUILD SUCCESS**: 54 passed, 0 failed, on the first run |
| Teardown | instance, VM, firewall, connection policy, subnet and VPC destroyed; run key and CA deleted; check: **nothing left** |

| Test | Result | Time |
|---|---|---|
| Cache conformance kit, logged in with the identity's access token over TLS against the instance's CA | **49/49 passed** | 12.1s |
| `iamTokenReadsAndWrites` | passed | 0.55s |
| `unauthenticatedConnectionIsRejected` | passed | 0.25s |
| `invalidTokenIsRejected`: `AUTH` with a made-up token | passed | 0.49s |
| `fullVerificationRejectsTheTunnelAddress`: with full checks, the certificate doesn't name 127.0.0.1 | passed | 0.25s |
| `chainIsRejectedWithoutTheInstanceCa`: the CA is private, and the JDK's public roots don't trust it | passed | 2.77s |

This is the first time Memorystore IAM authentication has been verified end to end. Before this,
`MemorystoreIamTokens` was only unit-tested, against a plain Valkey container with a password.

Unlike AWS tier 3, Memorystore IAM has no per-user access: the token's principal needs
`memorystore.instances.connect` (`roles/memorystore.dbConnectionUser`), and there are no database users to
mix up. So there is no "denied user" test. The rejection tests cover a missing and a forged token.

<details>
<summary>Cache conformance kit: 49/49 passed, 12.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `binaryValuesWithBytesCodec` | passed | 0.09 |
| `clearMatchesNamespaceLiterally` | passed | 0.20 |
| `clearRemovesOnlyThisNamespace` | passed | 0.85 |
| `clearWithoutNamespaceIsRefused` | passed | 0.00 |
| `concurrentGetOrLoadSharesOneLoad` | passed | 0.46 |
| `customCodec` | passed | 0.10 |
| `defaultTtlApplies` | passed | 0.07 |
| `deleteAllCountsExistingKeys` | passed | 0.15 |
| `deleteIfEqualsOnlyDeletesMatchingValue` | passed | 0.20 |
| `deleteReportsWhetherKeyExisted` | passed | 0.15 |
| `emptyBulkOperations` | passed | 0.01 |
| `entriesExpire` | passed | 0.46 |
| `expireOnMissingKeyReturnsFalse` | passed | 0.04 |
| `expireSetsNewTtl` | passed | 0.41 |
| `expiredLeaseCannotReleaseTheNextHoldersLock` | passed | 0.59 |
| `extendKeepsTheLockAlive` | passed | 0.84 |
| `failingLoaderCachesNothing` | passed | 0.25 |
| `getAndDeleteConsumesOnce` | passed | 0.15 |
| `getOrLoadLoadsOnceThenServesFromCache` | passed | 0.18 |
| `getOrLoadWithTtl` | passed | 0.15 |
| `incrementCreatesAndAdds` | passed | 0.16 |
| `incrementKeepsTtl` | passed | 0.19 |
| `incrementOnNonNumberFails` | passed | 0.08 |
| `incrementWithTtlStartsAWindowOnce` | passed | 0.92 |
| `invalidKeysAreRejected` | passed | 0.01 |
| `largeValue` | passed | 1.72 |
| `lockGivesUpAfterWaiting` | passed | 0.30 |
| `lockIsExclusiveUntilReleased` | passed | 0.16 |
| `lockWaitsForTheHolder` | passed | 0.52 |
| `missingKeyIsEmpty` | passed | 0.14 |
| `namespacesAreIsolated` | passed | 0.12 |
| `nonPositiveTtlIsRejected` | passed | 0.01 |
| `nullLoadResultIsNotCached` | passed | 0.15 |
| `putAllAndGetAll` | passed | 0.14 |
| `putAllWithTtl` | passed | 0.17 |
| `putIfAbsentOnlyStoresOnce` | passed | 0.14 |
| `putIfAbsentWithTtlExpires` | passed | 0.41 |
| `putOverwrites` | passed | 0.14 |
| `putThenGet` | passed | 0.09 |
| `replaceIsCompareAndSet` | passed | 0.27 |
| `reportsProviderAndNamespace` | passed | 0.00 |
| `statisticsCountHitsMissesAndLoads` | passed | 0.37 |
| `ttlOfMissingKeyIsEmpty` | passed | 0.04 |
| `ttlReportsRemainingTime` | passed | 0.08 |
| `unicodeKeysAndValues` | passed | 0.08 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateOfMissingKeyStoresNothing` | passed | 0.08 |
| `updateReplacesExistingValueAndKeepsTtl` | passed | 0.17 |
| `withoutTtlEntriesDoNotExpire` | passed | 0.11 |

</details>

## Tier 4: latest result

```bash
TIER=4 GCP_PROJECT=<project> GCP_REGION=australia-southeast1 cloud-testing/scripts/gcp-real-cloud.sh
```

Terraform (`cloud-testing/infra/gcp/tier4`) creates the following, in a VPC and subnet of the run's own:
- **Cluster:** a Managed Service for Apache Kafka cluster with the smallest capacity, 3 vCPUs and 3 GiB.
  Its brokers get Private Service Connect addresses in the subnet, and the service adds their DNS names to
  the VPC. There is no public endpoint.
- **Runner:** an `e2-small` Ubuntu 24.04 VM that runs as the test service account. A firewall rule allows
  SSH only from the test machine's `/32`, with a key generated for each run. Its startup script installs
  Java 21.
- **Claim checks:** a Cloud Storage bucket, so the claim-check test runs too.

Kafka clients connect to the bootstrap address, then to each broker by the name the cluster advertises, so
an SSH tunnel to one address, as in tier 3, can't work. The tests run on the runner instead. The script
builds them on the test machine, copies them with their classpath (`cloud-testing/scripts/in-vpc-runner.sh`, 172 jars,
141 MB), and runs `RealGcpStreamIT` there with the JUnit console launcher. The reports come back into
`halyx-gcp/target/failsafe-reports`. No credentials leave the test machine: the runner gets tokens from
its metadata server, as an application on Compute Engine would.

`RealGcpStreamIT` logs in with Google IAM through SASL `OAUTHBEARER`, the token wrapping the service
account's access token. It creates each test's topics through Kafka's admin API, logged in the same way, and
deletes older ones as it goes. Then `RealSpringStreamIT` runs on the runner on Spring Boot 3.5 and on Spring
Boot 4: in one Spring application, the starter's `StreamProducer` bean and `@HalyxListener`, and Spring for
Apache Kafka's `KafkaTemplate` and `@KafkaListener` logging in through `halyx.spring-kafka.cluster`. The
script stages the three test modules' jars into one directory on the runner and sends each jar once (313 MB
in all).

| | |
|---|---|
| Date | 2026-10-16, 00:30–01:04 UTC (34 min: cluster created in 12m 9s, tests 2m 25s, the rest staging, copying and teardown) |
| Run id | `r2610161130d877` |
| Runner | `e2-small`, OpenJDK 21.0.12.1; Kafka clients 3.9.2 (kit and Boot 3, with Spring for Apache Kafka 3.3.16) and 4.2.1 (Boot 4, with Spring for Apache Kafka 4.1.1) |
| Result | **all passed**: kit 25 of 25; Spring 2 of 2 |
| Teardown | cluster, runner, firewall rule, subnet, VPC and bucket destroyed; run key deleted; check: **nothing left** |

| Test | Result |
|---|---|
| Stream conformance kit, with Google IAM tokens over TLS | **25/25 passed** (123.2s) |
| `RealSpringStreamIT` on Spring Boot 3.5: starter, `KafkaTemplate` and `@KafkaListener` | **passed** (11.0s) |
| `RealSpringStreamIT` on Spring Boot 4 | **passed** (10.9s) |
| `largeValuesTravelThroughAClaimCheck` | passed: a 2 MiB value through the bucket |
| `idempotentProducersNeedTheCapability`, `transactionalSendAllIsVisibleToReadCommittedConsumers` | passed: Managed Kafka is Apache Kafka, so Halyx declares both capabilities, and both work |

The first tier 4 run (`r261016095774dd`) was the first time Managed Kafka's IAM login had been checked end to
end: until then its tokens had only been unit-tested (`ManagedKafkaTokensTest`). It passed first time and found
nothing to fix.

<details>
<summary>Stream conformance kit: 25/25 passed in the first run, 125.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `aFailingPartitionDoesNotHoldBackTheOthers` | passed | 4.33 |
| `binaryValuesArriveUnchanged` | passed | 4.24 |
| `closingTheListenerLetsTheRunningHandlerFinish` | passed | 7.28 |
| `committedPositionIsWhereTheGroupResumes` | passed | 9.10 |
| `everyGroupReadsEveryRecord` | passed | 7.56 |
| `exhaustedRecordGoesToTheDeadLetterTopic` | passed | 7.95 |
| `failedRecordIsRetriedBeforeTheRestOfItsPartition` | passed | 4.25 |
| `handledRecordsAreCommittedWhenTheListenerStops` | passed | 9.17 |
| `handlerSeesEveryRecordOfItsPartitionInOrder` | passed | 4.26 |
| `idempotentProducersNeedTheCapability` | passed | 4.28 |
| `largeValuesTravelThroughAClaimCheck` | passed | 5.43 |
| `latestStartSkipsEarlierRecords` | passed | 5.28 |
| `listenerHandlesEveryRecord` | passed | 4.81 |
| `membersOfAGroupShareThePartitions` | passed | 7.73 |
| `oversizedRecordWithoutClaimCheckIsRejected` | passed | 0.59 |
| `partitionCountMatchesTheTopic` | passed | 0.59 |
| `pollAndListenCannotBeMixed` | passed | 3.76 |
| `recordsWithTheSameKeyStayInOrder` | passed | 4.48 |
| `recordsWithoutKeyArrive` | passed | 4.56 |
| `reservedHeadersAreRejected` | passed | 0.57 |
| `sameKeyGoesToOnePartitionWithIncreasingOffsets` | passed | 0.80 |
| `sendAllReturnsResultsInOrder` | passed | 4.93 |
| `sentRecordArrivesWithKeyValueAndHeaders` | passed | 4.29 |
| `transactionalSendAllIsVisibleToReadCommittedConsumers` | passed | 5.40 |
| `uncommittedRecordsAreReadAgain` | passed | 7.87 |

</details>

## Run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r2610111704a192` | tier 1 | 176 passed, 1 failed, 1 error, 3 skipped | clean (checked by hand) | Pub/Sub `orderingKeyDeliversEachKeyInOrder` and Cloud Storage `updateMetadataWithStaleETagFails` failed (see below). The regional Parameter Manager check failed with a misleading `PERMISSION_DENIED`, and the script took the failure as empty. |
| `r2610111748599e` | tier 1 | **176/179, 3 skipped** | clean | |
| `r26101118357636` | tier 2 | 13/18 | clean | PostgreSQL 9/9. MySQL's five IAM tests logged in, then were refused the `app` database: IAM users have no privileges until granted. |
| `r2610111859cad1` | tier 2 | **18/18** | clean | The test now grants the MySQL IAM user `SELECT` on `app`. Login latency above is from this run. |
| `r261011193814e3` | tier 3 | **54/54** | clean | Latest tier 3 result, above, on the first run. Teardown took about 20 min: the VPC can only go once Memorystore's delete has finished. |
| `r261016095774dd` | tier 4 | **25/25** | clean | The first run. Cluster created in 24m 7s. |
| `r2610161130d877` | tier 4, plus Spring Boot 3 and 4 | **25/25; Spring 2/2** | clean | Latest tier 4 result, above. |
| `r26101613087031` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | 186/189, 3 skipped; Spring 0/2 | clean | Repositories 10/10 on Firestore. The Spring test compared whole records: Firestore keeps decimals as doubles, so a `BigDecimal` of 10 read back as 10.0, and the records weren't `equals`. The test now compares the value; the guide says so. |
| `r26101613452b74` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | **186/189, 3 skipped; Spring 2/2** | clean | Latest tier 1 result, above, after renaming the module to `halyx-spring-data-document`. |
| `r2610161518d9f9` | tier 2, plus R2DBC and Spring Boot 3 and 4 | **22/22; Spring 2/2** | clean | Latest tier 2 result, above. R2DBC's IAM login passed on both engines first time. |
| `r2610161942fea5` | tier 1, plus the event bus kit and Spring Boot 3 and 4 | 184 passed, 1 failed, 5 errors, 3 skipped; Spring 2/4 | clean | Eventarc 1/6 and both Spring event tests: the driver published to the regional `<location>-eventarcpublishing` endpoint, which answers 404 for message buses. Probing by hand showed a second problem: the pipeline's publish to Pub/Sub was refused with 403, having no credentials. Secret Manager `putReplacesWithANewVersion` read the previous value once. |
| `r26101620472de5` | tier 1, plus the event bus kit and Spring Boot 3 and 4 | 185 passed, 3 failed, 3 skipped; Spring **4/4** | clean | Global endpoint and authenticated pipeline: Eventarc 5/6. Pub/Sub refused (400) the pipeline's publish of the event without data. Secret Manager read a previous value twice; the kit now allows for `latest` being eventually consistent. |
| `r26101622034481` | tier 1, plus the event bus kit and Spring Boot 3 and 4 | **192 passed, 4 skipped**; Spring **4/4** | clean | Eventarc 6/7, with events without data declared a known gap. |
| `r2610170214c74e` | tier 1, plus halyx-reactor and Spring Boot 3 and 4 | **202 passed, 4 skipped**; Spring **10/10** | clean | Latest tier 1 result, above. Reactive repositories 10/10 on Firestore; the reactive Spring test (WebFlux over the bucket, a reactive repository, events through the reactive beans) 3/3 on Boot 3.5 and 4. |
| `r2610171333de9d` | tier 1, plus Spring Boot 3 and 4 | **205 passed, 4 skipped**; Spring **16/16** | clean | Latest tier 1 result, with the new Spring tests: `@HalyxListener` with a claim check (`RealSpringMessagingIT`) and `spring.config.import` with reload, health ping and the store bean's cache (`RealSpringParametersIT`) on Pub/Sub, Secret Manager and Parameter Manager. |
| `r2610171412729c` | tier 2, plus Spring Boot 3 and 4 | **22/22**; Spring **2/2** | clean | Latest tier 2 result. |
| `r261017143360ac` | tier 3, plus Spring Boot 3 and 4 | **55/55**; Spring **2/2** | clean | Latest tier 3 result: the cache kit with `ping`, then `Cache` beans with IAM over TLS (private CA), a JSON codec and `@Cacheable` on Boot 3.5 and 4. |
| `r2610171453c670` | tier 4, plus Spring Boot 3 and 4 | **25/25**; Spring **2/2** | clean | Latest tier 4 result. |
| `r26101721004590` | tier 1, plus Spring Boot 3 and 4 | **218 passed, 4 skipped**; Spring **16/16** | clean | Latest tier 1 result, with the asynchronous views (blob, document, messaging and event kits) on every service, non-blocking where the driver has a path: Firestore, Pub/Sub and Eventarc. |
| `r26101802235ad4` | tier 1, plus Spring Boot 3 and 4 | **221 passed, 4 skipped**; Spring **16/16** | clean | Latest tier 1 result, with asynchronous receives and settlement in the messaging kit, non-blocking on Pub/Sub. |
| `r2610181923c397` | tier 1, plus Spring Boot 3 and 4 | **230 passed, 4 skipped**; Spring **16/16** | clean | Latest tier 1 result, with the asynchronous parameter store: Secret Manager and Parameter Manager (global and regional) non-blocking, proven with a counting executor. |
| `r261022150030d1` | tier 1, plus Spring Boot 3 and 4 | **230 passed, 4 skipped**; Spring **16/16** | clean | Full regression before 1.0.0, at `be792e3`. |
| `r26102215401be8` | tier 2, plus Spring Boot 3 and 4 | **22/22**; Spring **2/2** | clean | Full regression before 1.0.0, at `be792e3`. |
| `r2610221559bb6b` | tier 3, plus Spring Boot 3 and 4 | **57/57**; Spring **2/2** | clean | Full regression before 1.0.0, at `be792e3`. |
| `r2610221620c638` | tier 4, plus Spring Boot 3 and 4 | **29/29**; Spring **2/2** | clean | Full regression before 1.0.0, at `be792e3`, with the kit's new listener tests: a replacement listener carries on where the last stopped, and a joining member waits for the previous owner's partitions. Cluster created in 13m 19s. |

## What real GCP found

The emulators (Floci-gcp, and Google's Pub/Sub and Firestore emulators) missed all of these:

| Finding | Fix |
|---|---|
| Pub/Sub rejects a publish request whose messages have different ordering keys (`FAILED_PRECONDITION: ... all messages must have no ordering key or they must all have the same ordering key`) | The publisher sends one request per ordering key, keeping each key's messages in order, and returns ids in the caller's order |
| `updateMetadata` with a stale ETag patched the *old* generation, which no longer exists in an unversioned bucket, so GCS answered 404 instead of 412 | Patch the live object with an `ifGenerationMatch` precondition, which GCS answers with 412 |
| `gcloud parametermanager` sends regional locations to the global endpoint and gets `PERMISSION_DENIED` | The test script uses the regional endpoint (`parametermanager.<region>.rep.googleapis.com`). The Java client was already correct |
| Cloud SQL for MySQL IAM users have no privileges, so a connection naming a database is refused (`Access denied for user 'halyx-test'@'%' to database 'app'`). PostgreSQL grants `CONNECT` to everyone | Documented in the README; the test grants `SELECT` as the admin, as an application's setup would |
| Memorystore's server certificate comes from a private, per-instance CA and names the private endpoint, so through a tunnel or proxy the name never matches | New `redis.verify-peer=ca` checks the chain against the CA (`redis.ca-certificate`) without the name. Full checks stay the default |
| Firestore collection-group queries need indexes the emulator doesn't ask for | Terraform creates them; the driver's documentation already lists them |
| Eventarc Advanced message buses aren't served by the regional `<location>-eventarcpublishing` endpoints, which answer 404 with an HTML page (they serve Eventarc Standard channels) | Publishing goes to the global `eventarcpublishing.googleapis.com` endpoint, whatever the bus's location |
| A pipeline with a Pub/Sub destination and no authentication sends its publish without credentials, and Pub/Sub refuses it with 403. The bus accepts the event, so the publisher sees no error | Terraform names a service account for the destination's OAuth token, and the Eventarc service agent gets Token Creator on it. The API guide says so; pipeline error logging is on in Terraform |
| Pub/Sub refuses (400) a pipeline's publish of an event without data, so such events never arrive | Not fixable in Halyx: the kit declares it a known gap on Eventarc, and the API guide says to give events data on Google Cloud |
| Secret Manager's `latest` alias is eventually consistent: a read right after a new version occasionally returned the previous one: 4 failed reads in 5 runs of the Secret Manager kit on 2026-10-16, after passing on every earlier run, but none in 39 probes (14 by REST, 25 by gRPC) | Google guarantees only reads by version number. The kit allows `overwriteLag()` on Secret Manager, as it allows listing and delete lag on AWS. The store doesn't wait |

## IAM

| Who | Role | Why |
|---|---|---|
| `halyx-test` service account | `roles/storage.admin` | create and destroy the bucket; read and write objects |
| | `roles/datastore.owner` | create and destroy the Firestore database and its indexes |
| | `roles/pubsub.admin` | create and delete topics and subscriptions; publish and pull |
| | `roles/secretmanager.admin` | secrets |
| | `roles/parametermanager.admin` | parameters, global and regional |
| | `roles/eventarc.admin` | create and delete the message bus, pipeline and enrollment; publish events |
| | `roles/eventarc.messageBusUser` | publish to the message bus (`eventarc.messageBuses.publish`), the role an application gets |
| | `roles/serviceusage.serviceUsageConsumer` | call APIs billed to the project |
| | `roles/compute.admin` (tier 3) | the run's VPC, subnet, firewall rule and bastion VM |
| | `roles/memorystore.admin` (tier 3) | create and delete the instance; read its CA |
| | `roles/memorystore.dbConnectionUser` (tier 3) | IAM login to the instance (`memorystore.instances.connect`), the role an application gets |
| | `roles/networkconnectivity.consumerNetworkAdmin` (tier 3) | the service-connection policy for Memorystore's private endpoint |
| | `roles/managedkafka.admin` (tier 4) | create and delete the cluster |
| | `roles/managedkafka.client` (tier 4) | IAM login to the cluster (`managedkafka.clusters.connect`), the role an application gets |
| | `roles/cloudsql.admin` (tier 2) | create instances and database users. It also includes what an application needs: `cloudsql.instances.login` for IAM database login (`roles/cloudsql.instanceUser`) and `cloudsql.instances.connect` for the connector (`roles/cloudsql.client`) |
| | `roles/iam.serviceAccountUser` on itself (tiers 1 and 4) | name itself as the Eventarc pipeline's publishing identity (tier 1); run the runner VM as itself (tier 4) |
| the Eventarc service agent (`service-<project number>@gcp-sa-eventarc.iam.gserviceaccount.com`) | `roles/iam.serviceAccountTokenCreator` on `halyx-test` | the pipeline publishes to Pub/Sub with an OAuth token for `halyx-test`, which the service agent mints |
| the person running the tests | `roles/iam.serviceAccountTokenCreator` on `halyx-test` | impersonate it. Project Owner doesn't include this |

The project also needs these APIs enabled: the IAM Service Account Credentials API
(`iamcredentials.googleapis.com`) for impersonation, Eventarc and Eventarc Publishing
(`eventarc.googleapis.com`, `eventarcpublishing.googleapis.com`) for tier 1's event bus, and the Cloud SQL Admin API
(`sqladmin.googleapis.com`) for tier 2, and Compute Engine, Memorystore, Network Connectivity and Service
Consumer Management (`compute`, `memorystore`, `networkconnectivity`, `serviceconsumermanagement`) for tier 3,
and Managed Service for Apache Kafka (`managedkafka`) for tier 4.

### IAM for the GKE run

`cloud-testing/scripts/gke-real-cloud.sh` runs the [portable orders service](../portable-service.md) on GKE. The nodes
and the pods run as `halyx-test`, which already holds every data-plane role above, so the service needs nothing new.
Creating the cluster and its registry does:

| Who | Role | Why |
|---|---|---|
| `halyx-test` service account | `roles/container.admin` | create and delete the cluster and its node pool; deploy to it |
| | `roles/artifactregistry.admin` | create and delete the run's repository and push the images; the nodes pull with it too |
| the Kubernetes service account `portable-orders/portable-orders` | `roles/iam.workloadIdentityUser` on `halyx-test` | the pods act as `halyx-test` through Workload Identity Federation for GKE |

The project also needs the Kubernetes Engine and Artifact Registry APIs (`container`, `artifactregistry`). The
workload identity binding can only be made once the project's workload identity pool exists, which happens when the
first cluster with Workload Identity is created. So the script makes the binding itself, right after the cluster is
created, as the person running it; on later runs it changes nothing.

```bash
gcloud services enable container.googleapis.com artifactregistry.googleapis.com
for role in roles/container.admin roles/artifactregistry.admin; do
  gcloud projects add-iam-policy-binding <project> --member=serviceAccount:halyx-test@<project>.iam.gserviceaccount.com \
      --role=$role --condition=None
done
```

