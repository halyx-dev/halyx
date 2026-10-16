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
| [1](#tier-1-latest-result) | Cloud Storage, Firestore, Pub/Sub, Secret Manager, Parameter Manager; Spring Data repositories and Spring Boot on Firestore | **186/189** passed, 3 skipped (expected); Spring **2/2** |
| [2](#tier-2-latest-result) | Cloud SQL for PostgreSQL and MySQL with IAM auth, direct and through the Cloud SQL connector | **18/18** |
| [3](#tier-3-latest-result) | Memorystore for Valkey with IAM auth and TLS (private CA) | **54/54** |
| [4](#tier-4-latest-result) | Streams on Managed Service for Apache Kafka with Google IAM, from a runner inside the VPC, plus Spring Boot 3 and 4 | **25/25**; Spring **2/2** |

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Tier 1: latest result

```bash
GCP_PROJECT=<project> GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
IT_TEST='RealGcpIT$Firestore' scripts/gcp-real-cloud.sh     # one suite only
```

**Identity.** Terraform and the tests act as a dedicated `halyx-test` service account, through
Application Default Credentials that impersonate it:

```bash
gcloud auth application-default login --impersonate-service-account=halyx-test@<project>.iam.gserviceaccount.com
```

**Provision.** Terraform (`infra/gcp/tier1`) creates the following, labelled `halyx-test=<run id>`:
- a Cloud Storage bucket, which also holds the messaging kit's claim-check payloads
- a Firestore database of the run's own, so the project's `(default)` database is never touched
- the composite indexes the document store's index queries need: `status` with `placedAt` ascending and
  descending, on both test tables, plus a collection-group index on `owner`

**Test.** `RealGcpIT` creates Pub/Sub topics and subscriptions named after the run, plus `tck-*` secrets
and parameters (Parameter Manager both global and regional).

**Tear down.** A sweep deletes what the tests left behind, then `terraform destroy` runs, then a check
lists every kind of resource. A listing that fails is reported as `CHECK FAILED`, never taken as empty.

| | |
|---|---|
| Date | 2026-10-16, 02:45–03:17 UTC (32 min: Firestore indexes about 7 min, tests about 15 min, teardown the rest) |
| Run id | `r26101613452b74` |
| Project and region | dedicated test project, `australia-southeast1` |
| Result | **BUILD SUCCESS**: 186 passed, 0 failed, 3 skipped (expected); Spring Boot repository test passed on Boot 3 and 4 |
| Teardown | bucket and Firestore database destroyed; check: **nothing left** |

| Service | Halyx API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| Cloud Storage | `BlobStore` | 75 | 74 | 1 | 200.0s |
| Firestore | `DocumentStore` | 23 | 23 | 0 | 16.2s |
| Firestore | Spring Data repositories | 10 | 10 | 0 | 5.3s |
| Pub/Sub | `Publisher` / `Subscription` | 15 | 13 | 2 | 282.1s |
| Secret Manager | `ParameterStore` | 22 | 22 | 0 | 207.9s |
| Parameter Manager, global | `ParameterStore` | 22 | 22 | 0 | 133.4s |
| Parameter Manager, `australia-southeast1` | `ParameterStore` | 22 | 22 | 0 | 36.3s |
| **Total** | | **189** | **186** | **3** | |
| Firestore, Spring Boot 3.5 | Spring Boot repository test | 1 | 1 | | |
| Firestore, Spring Boot 4 | Spring Boot repository test | 1 | 1 | | |

Skipped tests, all expected:

- **Cloud Storage `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't
  presign; GCS can.
- **Pub/Sub `repeatedDeduplicationIdIsDeliveredOnce` and `delayedMessageIsHeldBack`.** Pub/Sub has no
  publish-side deduplication and no per-message delay, so the driver declares neither capability.

Pub/Sub's time is mostly the redelivery tests, which wait for the 10s ack deadline to pass.

The repository kit and the Spring Boot repository test run on the run's `items` and `items-sorted`
collections and their `status`/`placedAt` composite indexes, through `halyx-spring-data-document`.

### Per-test results

<details>
<summary>Cloud Storage — BlobStore: 74/75 passed, 200.0s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 1.03 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.38 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.41 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.62 |
| `copyMissingSourceThrowsNotFound` | passed | 0.11 |
| `copyPreservesHttpHeaders` | passed | 0.49 |
| `defaultTierIsHot` | passed | 0.28 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 1.41 |
| `deleteAllSpansSeveralProviderBatches` | passed | 57.85 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.44 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.42 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.64 |
| `deleteMissingBlobIsIdempotent` | passed | 0.07 |
| `deleteRemovesBlob` | passed | 0.36 |
| `downloadWritesCompleteFile(Path)` | passed | 5.18 |
| `emptyBlobRoundTrips` | passed | 0.47 |
| `failedWriteKeepsExistingBlob` | passed | 0.46 |
| `failedWriteStoresNothing` | passed | 0.20 |
| `getMissingBlobThrowsNotFound` | passed | 0.08 |
| `getReturnsMetadataWithContent` | passed | 0.41 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.63 |
| `headMissingBlobReturnsEmpty` | passed | 0.16 |
| `headReturnsSizeAndLastModified` | passed | 0.33 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.65 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.85 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.38 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.61 |
| `listAllStreamsEveryBlob` | passed | 1.86 |
| `listEmptyPrefixReturnsNothing` | passed | 0.07 |
| `listFiltersByPrefix` | passed | 0.85 |
| `listPaginatesInKeyOrder` | passed | 2.80 |
| `listSummariesCarrySize` | passed | 0.32 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 1.33 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 1.55 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.31 |
| `openEndedRangeReadsToEnd` | passed | 0.37 |
| `parallelReadReassemblesRangesInOrder` | passed | 3.14 |
| `parallelReadValidatesChecksum` | passed | 3.40 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 0.97 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 11.50 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.96 |
| `presignedUploadUrlUploadsBlob` | passed | 0.95 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 1.29 |
| `presignedUrlReportsExpiry` | passed | 0.28 |
| `putFromFile(Path)` | passed | 0.38 |
| `putFromStream` | passed | 0.41 |
| `putLargeFile(Path)` | passed | 7.72 |
| `putOverwritesExistingBlob` | passed | 0.60 |
| `putReturnsETagMatchingHead` | passed | 0.29 |
| `putSmallUnknownLengthStream` | passed | 0.43 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.32 |
| `putStoresStandardHttpHeaders` | passed | 0.31 |
| `putThenGetRoundTripsContent` | passed | 0.43 |
| `putUnknownLengthStream` | passed | 8.65 |
| `putWithTierStoresTier` | passed | 0.57 |
| `rangeReadReturnsSlice` | passed | 0.39 |
| `setTierChangesTierButNotContent` | passed | 0.79 |
| `setTierToCurrentTierIsANoOp` | passed | 0.95 |
| `storedChecksumsMatchTheContent` | passed | 0.28 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.45 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.08 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.88 |
| `updateMetadataWithStaleETagFails` | passed | 0.65 |
| `validatedFileAndStreamUploads(Path)` | passed | 1.70 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 12.04 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.45 |
| `validatedWriteInParts` | passed | 13.64 |
| `writeEmptyBlob` | passed | 0.37 |
| `writeExactMultipleOfPartSize` | passed | 10.57 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 17.90 |
| `writeLargeBlobInParts` | passed | 11.35 |
| `writeSmallBlobInOneRequest` | passed | 0.49 |

</details>

<details>
<summary>Firestore — DocumentStore: 23/23 passed, 16.2s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.63 |
| `concurrentIncrementsAreNotLost` | passed | 0.91 |
| `decimalsRoundTripAsNumbers` | passed | 0.13 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.44 |
| `expiredItemsAreNeverReturned` | passed | 3.83 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 0.98 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.39 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.25 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 3.24 |
| `indexQueryPagesThroughManyMatches` | passed | 0.39 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.27 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.48 |
| `missingItemIsEmpty` | passed | 0.11 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.15 |
| `putReplacesTheWholeItem` | passed | 0.18 |
| `queryPagesThroughLargePartitions` | passed | 0.93 |
| `queryReturnsAPartitionInSortOrder` | passed | 0.53 |
| `transactionAppliesEveryWriteTogether` | passed | 0.76 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.42 |
| `updateSetsRemovesAndIncrements` | passed | 0.25 |
| `versionsGuardConcurrentWrites` | passed | 0.91 |

</details>

<details>
<summary>Firestore — Spring Data repositories: 10/10 passed, 5.3s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `indexQueriesAcrossPartitions` | passed | 0.59 |
| `indexWithoutASortAttribute` | passed | 0.28 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.68 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.19 |
| `sortKeyConditions` | passed | 0.64 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 1.19 |
| `streamsReadLazily` | passed | 0.29 |
| `unversionedEntitiesAreSavedInBatchesAndOverwritten` | passed | 0.64 |
| `uuidKeysAndStringIds` | passed | 0.24 |
| `windowsScrollThroughAPartition` | passed | 0.55 |

</details>

<details>
<summary>Pub/Sub — Publisher / Subscription: 13/15 passed, 282.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 27.34 |
| `binaryBodiesArriveUnchanged` | passed | 14.02 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 20.61 |
| `delayedMessageIsHeldBack` | skipped | 0.00 |
| `extendedLeaseKeepsMessageHidden` | passed | 35.02 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 35.68 |
| `listenerLimitsConcurrency` | passed | 25.09 |
| `nackedMessageIsRedelivered` | passed | 17.43 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 23.57 |
| `publishAllDeliversEveryMessage` | passed | 18.14 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 15.03 |
| `repeatedDeduplicationIdIsDeliveredOnce` | skipped | 0.00 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 11.26 |
| `settlingTwiceIsRejected` | passed | 14.81 |
| `unsettledMessageReturnsAfterItsLease` | passed | 24.15 |

</details>

<details>
<summary>Secret Manager — ParameterStore: 22/22 passed, 207.9s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 10.45 |
| `deleteIsIdempotent` | passed | 9.12 |
| `deletedNamesCanBeReused` | passed | 13.25 |
| `earlierVersionsCanBeRead` | passed | 10.02 |
| `getAllDoesNotMatchPartialSegments` | passed | 15.32 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 26.32 |
| `invalidNamesAreRejected` | passed | 0.13 |
| `jsonFieldsCanBeRead` | passed | 7.33 |
| `largeValues` | passed | 7.36 |
| `missingIsEmptyAndRequireFails` | passed | 1.25 |
| `nativeNamesAreUsedAsTheyAre` | passed | 7.93 |
| `plainValuesHaveNoFields` | passed | 6.68 |
| `putReplacesWithANewVersion` | passed | 11.02 |
| `putThenGet` | passed | 7.47 |
| `reportsKindAndProvider` | passed | 0.15 |
| `statisticsCountCacheHits` | passed | 6.97 |
| `storesAreIsolatedByPrefix` | passed | 11.01 |
| `typedReads` | passed | 6.78 |
| `valuesKeepSpecialCharactersAndLines` | passed | 7.48 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 18.81 |
| `watchedChangesInvalidateTheCache` | passed | 11.67 |
| `writesInvalidateTheirOwnCache` | passed | 11.39 |

</details>

<details>
<summary>Parameter Manager, global — ParameterStore: 22/22 passed, 133.4s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 6.93 |
| `deleteIsIdempotent` | passed | 4.09 |
| `deletedNamesCanBeReused` | passed | 7.67 |
| `earlierVersionsCanBeRead` | passed | 5.49 |
| `getAllDoesNotMatchPartialSegments` | passed | 10.26 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 15.36 |
| `invalidNamesAreRejected` | passed | 0.23 |
| `jsonFieldsCanBeRead` | passed | 6.18 |
| `largeValues` | passed | 4.86 |
| `missingIsEmptyAndRequireFails` | passed | 0.81 |
| `nativeNamesAreUsedAsTheyAre` | passed | 5.86 |
| `plainValuesHaveNoFields` | passed | 4.74 |
| `putReplacesWithANewVersion` | passed | 6.18 |
| `putThenGet` | passed | 5.42 |
| `reportsKindAndProvider` | passed | 0.23 |
| `statisticsCountCacheHits` | passed | 4.62 |
| `storesAreIsolatedByPrefix` | passed | 6.95 |
| `typedReads` | passed | 4.64 |
| `valuesKeepSpecialCharactersAndLines` | passed | 4.61 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 13.41 |
| `watchedChangesInvalidateTheCache` | passed | 8.19 |
| `writesInvalidateTheirOwnCache` | passed | 6.66 |

</details>

<details>
<summary>Parameter Manager, `australia-southeast1` — ParameterStore: 22/22 passed, 36.3s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 1.74 |
| `deleteIsIdempotent` | passed | 1.03 |
| `deletedNamesCanBeReused` | passed | 2.03 |
| `earlierVersionsCanBeRead` | passed | 1.59 |
| `getAllDoesNotMatchPartialSegments` | passed | 2.84 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 3.90 |
| `invalidNamesAreRejected` | passed | 0.07 |
| `jsonFieldsCanBeRead` | passed | 1.66 |
| `largeValues` | passed | 1.25 |
| `missingIsEmptyAndRequireFails` | passed | 0.19 |
| `nativeNamesAreUsedAsTheyAre` | passed | 1.54 |
| `plainValuesHaveNoFields` | passed | 1.40 |
| `putReplacesWithANewVersion` | passed | 1.53 |
| `putThenGet` | passed | 1.41 |
| `reportsKindAndProvider` | passed | 0.07 |
| `statisticsCountCacheHits` | passed | 1.21 |
| `storesAreIsolatedByPrefix` | passed | 2.32 |
| `typedReads` | passed | 1.32 |
| `valuesKeepSpecialCharactersAndLines` | passed | 1.24 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 3.51 |
| `watchedChangesInvalidateTheCache` | passed | 2.59 |
| `writesInvalidateTheirOwnCache` | passed | 1.84 |

</details>

## Tier 2: latest result

```bash
TIER=2 GCP_PROJECT=<project> GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
```

Terraform (`infra/gcp/tier2`) creates Cloud SQL for PostgreSQL 17 and for MySQL 8.4:
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
| Date | 2026-10-11, 07:59–08:21 UTC (22 min: PostgreSQL ready in 11m 34s, MySQL in 16m 14s, tests 1m 57s, teardown about 4 min) |
| Run id | `r2610111859cad1` |
| Engines | PostgreSQL 17, MySQL 8.4 |
| Result | **BUILD SUCCESS**: 18 passed, 0 failed |
| Teardown | instances (with their databases and users) destroyed; state deleted; check: **nothing left** |

| Test | PostgreSQL | MySQL |
|---|---|---|
| `loginLatency`: runs first and times each login (see below); a warm direct-token login must take under 3s | passed (6.1s) | passed (35.1s) |
| `passwordLoginRunsQueriesOverTls`: direct, and the server reports the session encrypted | passed (0.9s) | passed (3.3s) |
| `iamLoginWithDerivedUserRunsQueriesOverTls`: `SqlAuth.iam()` derives the user from the service account | passed (1.3s) | passed (17.7s) |
| `iamLoginWithExplicitUser`: `SqlAuth.iam("<user>")` | passed (1.2s) | passed (4.3s) |
| `connectorIamLogin`: `gcp.sql.instance` through the Cloud SQL Java connector, no host or authorized network | passed (0.9s) | passed (4.1s) |
| `connectorPasswordLogin` | passed (1.1s) | passed (1.4s) |
| `replacedConnectionsLogInAgainWithTokens`: three rounds of filling the pool, then evicting it | passed (6.8s) | passed (13.9s) |
| `tokenDoesNotLogInAUserNotEnabledForIam`: a token for `halyx_password_only` is rejected | passed (3.1s) | passed (2.9s) |
| `wrongPasswordDoesNotLogIn` | passed (1.3s) | passed (2.0s) |

### Login latency

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
TIER=3 GCP_PROJECT=<project> GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
```

Terraform (`infra/gcp/tier3`) creates the following, in a VPC and subnet of the run's own:
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
TIER=4 GCP_PROJECT=<project> GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
```

Terraform (`infra/gcp/tier4`) creates the following, in a VPC and subnet of the run's own:
- **Cluster:** a Managed Service for Apache Kafka cluster with the smallest capacity, 3 vCPUs and 3 GiB.
  Its brokers get Private Service Connect addresses in the subnet, and the service adds their DNS names to
  the VPC. There is no public endpoint.
- **Runner:** an `e2-small` Ubuntu 24.04 VM that runs as the test service account. A firewall rule allows
  SSH only from the test machine's `/32`, with a key generated for each run. Its startup script installs
  Java 21.
- **Claim checks:** a Cloud Storage bucket, so the claim-check test runs too.

Kafka clients connect to the bootstrap address, then to each broker by the name the cluster advertises, so
an SSH tunnel to one address, as in tier 3, can't work. The tests run on the runner instead. The script
builds them on the test machine, copies them with their classpath (`scripts/in-vpc-runner.sh`, 172 jars,
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
| `r2610111859cad1` | tier 2 | **18/18** | clean | Latest tier 2 result, above. The test now grants the MySQL IAM user `SELECT` on `app`. |
| `r261011193814e3` | tier 3 | **54/54** | clean | Latest tier 3 result, above, on the first run. Teardown took about 20 min: the VPC can only go once Memorystore's delete has finished. |
| `r261016095774dd` | tier 4 | **25/25** | clean | The first run. Cluster created in 24m 7s. |
| `r2610161130d877` | tier 4, plus Spring Boot 3 and 4 | **25/25; Spring 2/2** | clean | Latest tier 4 result, above. |
| `r26101613087031` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | 186/189, 3 skipped; Spring 0/2 | clean | Repositories 10/10 on Firestore. The Spring test compared whole records: Firestore keeps decimals as doubles, so a `BigDecimal` of 10 read back as 10.0, and the records weren't `equals`. The test now compares the value; the guide says so. |
| `r26101613452b74` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | **186/189, 3 skipped; Spring 2/2** | clean | Latest tier 1 result, above, after renaming the module to `halyx-spring-data-document`. |

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

## IAM

| Who | Role | Why |
|---|---|---|
| `halyx-test` service account | `roles/storage.admin` | create and destroy the bucket; read and write objects |
| | `roles/datastore.owner` | create and destroy the Firestore database and its indexes |
| | `roles/pubsub.admin` | create and delete topics and subscriptions; publish and pull |
| | `roles/secretmanager.admin` | secrets |
| | `roles/parametermanager.admin` | parameters, global and regional |
| | `roles/serviceusage.serviceUsageConsumer` | call APIs billed to the project |
| | `roles/compute.admin` (tier 3) | the run's VPC, subnet, firewall rule and bastion VM |
| | `roles/memorystore.admin` (tier 3) | create and delete the instance; read its CA |
| | `roles/memorystore.dbConnectionUser` (tier 3) | IAM login to the instance (`memorystore.instances.connect`), the role an application gets |
| | `roles/networkconnectivity.consumerNetworkAdmin` (tier 3) | the service-connection policy for Memorystore's private endpoint |
| | `roles/managedkafka.admin` (tier 4) | create and delete the cluster |
| | `roles/managedkafka.client` (tier 4) | IAM login to the cluster (`managedkafka.clusters.connect`), the role an application gets |
| | `roles/iam.serviceAccountUser` on itself (tier 4) | run the runner VM as the test service account |
| | `roles/cloudsql.admin` (tier 2) | create instances and database users. It also includes what an application needs: `cloudsql.instances.login` for IAM database login (`roles/cloudsql.instanceUser`) and `cloudsql.instances.connect` for the connector (`roles/cloudsql.client`) |
| the person running the tests | `roles/iam.serviceAccountTokenCreator` on `halyx-test` | impersonate it. Project Owner doesn't include this |

The project also needs these APIs enabled: the IAM Service Account Credentials API
(`iamcredentials.googleapis.com`) for impersonation, and the Cloud SQL Admin API
(`sqladmin.googleapis.com`) for tier 2, and Compute Engine, Memorystore, Network Connectivity and Service
Consumer Management (`compute`, `memorystore`, `networkconnectivity`, `serviceconsumermanagement`) for tier 3,
and Managed Service for Apache Kafka (`managedkafka`) for tier 4.
