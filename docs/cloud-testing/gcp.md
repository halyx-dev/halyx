---
title: GCP
parent: Cloud testing
nav_order: 2
---

# Google Cloud: real-cloud results
{: .no_toc }

Results of running the Cirrus conformance kits against real Google Cloud services in `australia-southeast1` (Sydney), acting as
the `cirrus-test` service account, impersonated through Application Default Credentials. Every run creates its own throwaway resources and deletes them afterwards. See
[Cloud testing](index.md) for how the runs work.

| Tier | Services | Latest result |
|---|---|---|
| [1](#tier-1-latest-result) | Cloud Storage, Firestore, Pub/Sub, Secret Manager, Parameter Manager | **176/179** passed, 3 skipped (expected) |
| [2](#tier-2-latest-result) | Cloud SQL for PostgreSQL and MySQL with IAM auth, direct and through the Cloud SQL connector | **18/18** |
| [3](#tier-3-latest-result) | Memorystore for Valkey with IAM auth and TLS (private CA) | **54/54** |

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

**Identity.** Terraform and the tests act as a dedicated `cirrus-test` service account, through
Application Default Credentials that impersonate it:

```bash
gcloud auth application-default login --impersonate-service-account=cirrus-test@<project>.iam.gserviceaccount.com
```

**Provision.** Terraform (`infra/gcp/tier1`) creates the following, labelled `cirrus-test=<run id>`:
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
| Date | 2026-10-11, 06:48–07:19 UTC (31 min: Firestore indexes about 7 min, tests about 14 min, teardown the rest) |
| Run id | `r2610111748599e` |
| Project and region | dedicated test project, `australia-southeast1` |
| Result | **BUILD SUCCESS**: 176 passed, 0 failed, 3 skipped (expected) |
| Teardown | bucket and Firestore database destroyed; check: **nothing left** |

| Service | Cirrus API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| Cloud Storage | `BlobStore` | 75 | 74 | 1 | 183.8s |
| Firestore | `DocumentStore` | 23 | 23 | 0 | 47.4s |
| Pub/Sub | `Publisher` / `Subscription` | 15 | 13 | 2 | 292.8s |
| Secret Manager | `ParameterStore` | 22 | 22 | 0 | 173.1s |
| Parameter Manager, global | `ParameterStore` | 22 | 22 | 0 | 113.1s |
| Parameter Manager, `australia-southeast1` | `ParameterStore` | 22 | 22 | 0 | 42.5s |
| **Total** | | **179** | **176** | **3** | |

Skipped tests, all expected:

- **Cloud Storage `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't
  presign; GCS can.
- **Pub/Sub `repeatedDeduplicationIdIsDeliveredOnce` and `delayedMessageIsHeldBack`.** Pub/Sub has no
  publish-side deduplication and no per-message delay, so the driver declares neither capability.

Pub/Sub's time is mostly the redelivery tests, which wait for the 10s ack deadline to pass.

### Per-test results

<details>
<summary>Cloud Storage — BlobStore: 74/75 passed, 183.8s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 1.14 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.36 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.39 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.60 |
| `copyMissingSourceThrowsNotFound` | passed | 0.08 |
| `copyPreservesHttpHeaders` | passed | 0.42 |
| `defaultTierIsHot` | passed | 0.26 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 1.34 |
| `deleteAllSpansSeveralProviderBatches` | passed | 55.20 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.42 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.38 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.65 |
| `deleteMissingBlobIsIdempotent` | passed | 0.05 |
| `deleteRemovesBlob` | passed | 0.32 |
| `downloadWritesCompleteFile(Path)` | passed | 2.36 |
| `emptyBlobRoundTrips` | passed | 0.41 |
| `failedWriteKeepsExistingBlob` | passed | 0.43 |
| `failedWriteStoresNothing` | passed | 0.21 |
| `getMissingBlobThrowsNotFound` | passed | 0.07 |
| `getReturnsMetadataWithContent` | passed | 0.36 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.60 |
| `headMissingBlobReturnsEmpty` | passed | 0.16 |
| `headReturnsSizeAndLastModified` | passed | 0.31 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.60 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.80 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.32 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.53 |
| `listAllStreamsEveryBlob` | passed | 1.52 |
| `listEmptyPrefixReturnsNothing` | passed | 0.07 |
| `listFiltersByPrefix` | passed | 0.74 |
| `listPaginatesInKeyOrder` | passed | 1.65 |
| `listSummariesCarrySize` | passed | 0.34 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 1.34 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 1.44 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.28 |
| `openEndedRangeReadsToEnd` | passed | 0.34 |
| `parallelReadReassemblesRangesInOrder` | passed | 4.45 |
| `parallelReadValidatesChecksum` | passed | 2.83 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 2.05 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 11.77 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.96 |
| `presignedUploadUrlUploadsBlob` | passed | 0.88 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 0.90 |
| `presignedUrlReportsExpiry` | passed | 0.21 |
| `putFromFile(Path)` | passed | 0.37 |
| `putFromStream` | passed | 0.36 |
| `putLargeFile(Path)` | passed | 7.35 |
| `putOverwritesExistingBlob` | passed | 0.54 |
| `putReturnsETagMatchingHead` | passed | 0.27 |
| `putSmallUnknownLengthStream` | passed | 0.36 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.30 |
| `putStoresStandardHttpHeaders` | passed | 0.29 |
| `putThenGetRoundTripsContent` | passed | 0.37 |
| `putUnknownLengthStream` | passed | 11.24 |
| `putWithTierStoresTier` | passed | 0.53 |
| `rangeReadReturnsSlice` | passed | 0.38 |
| `setTierChangesTierButNotContent` | passed | 0.79 |
| `setTierToCurrentTierIsANoOp` | passed | 0.81 |
| `storedChecksumsMatchTheContent` | passed | 0.29 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.41 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.07 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.75 |
| `updateMetadataWithStaleETagFails` | passed | 0.55 |
| `validatedFileAndStreamUploads(Path)` | passed | 1.17 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 13.98 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.39 |
| `validatedWriteInParts` | passed | 13.03 |
| `writeEmptyBlob` | passed | 0.37 |
| `writeExactMultipleOfPartSize` | passed | 10.25 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 7.47 |
| `writeLargeBlobInParts` | passed | 10.15 |
| `writeSmallBlobInOneRequest` | passed | 0.46 |

</details>

<details>
<summary>Firestore — DocumentStore: 23/23 passed, 47.4s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 2.98 |
| `concurrentIncrementsAreNotLost` | passed | 3.62 |
| `decimalsRoundTripAsNumbers` | passed | 0.53 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 1.86 |
| `expiredItemsAreNeverReturned` | passed | 6.28 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 4.32 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 1.37 |
| `ifNotExistsCreatesOnlyOnce` | passed | 1.03 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 4.00 |
| `indexQueryPagesThroughManyMatches` | passed | 1.38 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 1.11 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.01 |
| `keysWithSpecialCharactersRoundTrip` | passed | 2.10 |
| `missingItemIsEmpty` | passed | 0.53 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.54 |
| `putReplacesTheWholeItem` | passed | 0.79 |
| `queryPagesThroughLargePartitions` | passed | 3.38 |
| `queryReturnsAPartitionInSortOrder` | passed | 2.14 |
| `transactionAppliesEveryWriteTogether` | passed | 2.93 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 1.91 |
| `updateSetsRemovesAndIncrements` | passed | 1.14 |
| `versionsGuardConcurrentWrites` | passed | 3.41 |

</details>

<details>
<summary>Pub/Sub — messaging: 13/15 passed, 292.8s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 30.24 |
| `binaryBodiesArriveUnchanged` | passed | 15.96 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 22.62 |
| `delayedMessageIsHeldBack` | skipped | 0.00 |
| `extendedLeaseKeepsMessageHidden` | passed | 35.10 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 35.91 |
| `listenerLimitsConcurrency` | passed | 24.69 |
| `nackedMessageIsRedelivered` | passed | 16.57 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 21.52 |
| `publishAllDeliversEveryMessage` | passed | 23.24 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 15.88 |
| `repeatedDeduplicationIdIsDeliveredOnce` | skipped | 0.00 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 9.69 |
| `settlingTwiceIsRejected` | passed | 17.30 |
| `unsettledMessageReturnsAfterItsLease` | passed | 24.08 |

</details>

<details>
<summary>Secret Manager — ParameterStore: 22/22 passed, 173.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 8.02 |
| `deleteIsIdempotent` | passed | 5.61 |
| `deletedNamesCanBeReused` | passed | 10.01 |
| `earlierVersionsCanBeRead` | passed | 7.20 |
| `getAllDoesNotMatchPartialSegments` | passed | 16.20 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 19.77 |
| `invalidNamesAreRejected` | passed | 0.51 |
| `jsonFieldsCanBeRead` | passed | 7.35 |
| `largeValues` | passed | 7.50 |
| `missingIsEmptyAndRequireFails` | passed | 0.96 |
| `nativeNamesAreUsedAsTheyAre` | passed | 7.10 |
| `plainValuesHaveNoFields` | passed | 5.20 |
| `putReplacesWithANewVersion` | passed | 7.97 |
| `putThenGet` | passed | 7.20 |
| `reportsKindAndProvider` | passed | 0.30 |
| `statisticsCountCacheHits` | passed | 5.50 |
| `storesAreIsolatedByPrefix` | passed | 7.49 |
| `typedReads` | passed | 7.35 |
| `valuesKeepSpecialCharactersAndLines` | passed | 7.13 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 15.77 |
| `watchedChangesInvalidateTheCache` | passed | 9.87 |
| `writesInvalidateTheirOwnCache` | passed | 9.13 |

</details>

<details>
<summary>Parameter Manager (global) — ParameterStore: 22/22 passed, 113.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 5.55 |
| `deleteIsIdempotent` | passed | 3.23 |
| `deletedNamesCanBeReused` | passed | 6.10 |
| `earlierVersionsCanBeRead` | passed | 4.55 |
| `getAllDoesNotMatchPartialSegments` | passed | 9.30 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 12.64 |
| `invalidNamesAreRejected` | passed | 0.23 |
| `jsonFieldsCanBeRead` | passed | 5.71 |
| `largeValues` | passed | 4.20 |
| `missingIsEmptyAndRequireFails` | passed | 0.71 |
| `nativeNamesAreUsedAsTheyAre` | passed | 4.97 |
| `plainValuesHaveNoFields` | passed | 4.17 |
| `putReplacesWithANewVersion` | passed | 4.74 |
| `putThenGet` | passed | 4.42 |
| `reportsKindAndProvider` | passed | 0.23 |
| `statisticsCountCacheHits` | passed | 4.10 |
| `storesAreIsolatedByPrefix` | passed | 6.10 |
| `typedReads` | passed | 4.07 |
| `valuesKeepSpecialCharactersAndLines` | passed | 3.81 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 11.45 |
| `watchedChangesInvalidateTheCache` | passed | 7.00 |
| `writesInvalidateTheirOwnCache` | passed | 5.86 |

</details>

<details>
<summary>Parameter Manager (australia-southeast1) — ParameterStore: 22/22 passed, 42.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 1.77 |
| `deleteIsIdempotent` | passed | 1.21 |
| `deletedNamesCanBeReused` | passed | 2.57 |
| `earlierVersionsCanBeRead` | passed | 1.66 |
| `getAllDoesNotMatchPartialSegments` | passed | 2.88 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 4.77 |
| `invalidNamesAreRejected` | passed | 0.06 |
| `jsonFieldsCanBeRead` | passed | 1.87 |
| `largeValues` | passed | 1.46 |
| `missingIsEmptyAndRequireFails` | passed | 0.24 |
| `nativeNamesAreUsedAsTheyAre` | passed | 1.61 |
| `plainValuesHaveNoFields` | passed | 1.87 |
| `putReplacesWithANewVersion` | passed | 1.62 |
| `putThenGet` | passed | 1.75 |
| `reportsKindAndProvider` | passed | 0.06 |
| `statisticsCountCacheHits` | passed | 1.34 |
| `storesAreIsolatedByPrefix` | passed | 4.14 |
| `typedReads` | passed | 1.40 |
| `valuesKeepSpecialCharactersAndLines` | passed | 1.41 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 4.00 |
| `watchedChangesInvalidateTheCache` | passed | 2.77 |
| `writesInvalidateTheirOwnCache` | passed | 2.02 |

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
- `cirrus_admin`, a built-in user with a password generated for the run
- the test service account as an IAM user: `cirrus-test@<project>.iam` on PostgreSQL and `cirrus-test`
  on MySQL
- `cirrus_password_only`, a built-in user that an IAM token must not log in

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
| `tokenDoesNotLogInAUserNotEnabledForIam`: a token for `cirrus_password_only` is rejected | passed (3.1s) | passed (2.9s) |
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
  bottleneck, not IAM or Cirrus, and a larger tier would bring it close to PostgreSQL. Its warm token
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

## Run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r2610111704a192` | tier 1 | 176 passed, 1 failed, 1 error, 3 skipped | clean (checked by hand) | Pub/Sub `orderingKeyDeliversEachKeyInOrder` and Cloud Storage `updateMetadataWithStaleETagFails` failed (see below). The regional Parameter Manager check failed with a misleading `PERMISSION_DENIED`, and the script took the failure as empty. |
| `r2610111748599e` | tier 1 | **176/179, 3 skipped** | clean | Latest tier 1 result, above. |
| `r26101118357636` | tier 2 | 13/18 | clean | PostgreSQL 9/9. MySQL's five IAM tests logged in, then were refused the `app` database: IAM users have no privileges until granted. |
| `r2610111859cad1` | tier 2 | **18/18** | clean | Latest tier 2 result, above. The test now grants the MySQL IAM user `SELECT` on `app`. |
| `r261011193814e3` | tier 3 | **54/54** | clean | Latest tier 3 result, above, on the first run. Teardown took about 20 min: the VPC can only go once Memorystore's delete has finished. |

## What real GCP found

The emulators (Floci-gcp, and Google's Pub/Sub and Firestore emulators) missed all of these:

| Finding | Fix |
|---|---|
| Pub/Sub rejects a publish request whose messages have different ordering keys (`FAILED_PRECONDITION: ... all messages must have no ordering key or they must all have the same ordering key`) | The publisher sends one request per ordering key, keeping each key's messages in order, and returns ids in the caller's order |
| `updateMetadata` with a stale ETag patched the *old* generation, which no longer exists in an unversioned bucket, so GCS answered 404 instead of 412 | Patch the live object with an `ifGenerationMatch` precondition, which GCS answers with 412 |
| `gcloud parametermanager` sends regional locations to the global endpoint and gets `PERMISSION_DENIED` | The test script uses the regional endpoint (`parametermanager.<region>.rep.googleapis.com`). The Java client was already correct |
| Cloud SQL for MySQL IAM users have no privileges, so a connection naming a database is refused (`Access denied for user 'cirrus-test'@'%' to database 'app'`). PostgreSQL grants `CONNECT` to everyone | Documented in the README; the test grants `SELECT` as the admin, as an application's setup would |
| Memorystore's server certificate comes from a private, per-instance CA and names the private endpoint, so through a tunnel or proxy the name never matches | New `redis.verify-peer=ca` checks the chain against the CA (`redis.ca-certificate`) without the name. Full checks stay the default |
| Firestore collection-group queries need indexes the emulator doesn't ask for | Terraform creates them; the driver's documentation already lists them |

## IAM

| Who | Role | Why |
|---|---|---|
| `cirrus-test` service account | `roles/storage.admin` | create and destroy the bucket; read and write objects |
| | `roles/datastore.owner` | create and destroy the Firestore database and its indexes |
| | `roles/pubsub.admin` | create and delete topics and subscriptions; publish and pull |
| | `roles/secretmanager.admin` | secrets |
| | `roles/parametermanager.admin` | parameters, global and regional |
| | `roles/serviceusage.serviceUsageConsumer` | call APIs billed to the project |
| | `roles/compute.admin` (tier 3) | the run's VPC, subnet, firewall rule and bastion VM |
| | `roles/memorystore.admin` (tier 3) | create and delete the instance; read its CA |
| | `roles/memorystore.dbConnectionUser` (tier 3) | IAM login to the instance (`memorystore.instances.connect`), the role an application gets |
| | `roles/networkconnectivity.consumerNetworkAdmin` (tier 3) | the service-connection policy for Memorystore's private endpoint |
| | `roles/cloudsql.admin` (tier 2) | create instances and database users. It also includes what an application needs: `cloudsql.instances.login` for IAM database login (`roles/cloudsql.instanceUser`) and `cloudsql.instances.connect` for the connector (`roles/cloudsql.client`) |
| the person running the tests | `roles/iam.serviceAccountTokenCreator` on `cirrus-test` | impersonate it. Project Owner doesn't include this |

The project also needs these APIs enabled: the IAM Service Account Credentials API
(`iamcredentials.googleapis.com`) for impersonation, and the Cloud SQL Admin API
(`sqladmin.googleapis.com`) for tier 2, and Compute Engine, Memorystore, Network Connectivity and Service
Consumer Management (`compute`, `memorystore`, `networkconnectivity`, `serviceconsumermanagement`) for tier 3.
