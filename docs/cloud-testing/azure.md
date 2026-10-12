---
title: Azure
parent: Cloud testing
nav_order: 3
---

# Microsoft Azure: real-cloud results
{: .no_toc }

Results of running the Halyx conformance kits against real Microsoft Azure services in `australiaeast` (Sydney), acting as
the `halyx-test` service principal. Every run creates its own throwaway resources and deletes them afterwards. See
[Cloud testing](index.md) for how the runs work.

| Tier | Services | Latest result |
|---|---|---|
| [1](#tier-1-latest-result) | Blob Storage, Cosmos DB, Service Bus, Key Vault, App Configuration (Entra ID only) | **156/158** passed, 2 skipped (expected) |
| [2](#tier-2-latest-result) | Azure Database for PostgreSQL and Azure SQL with Entra ID login | **11/11** |
| [3](#tier-3-latest-result) | Azure Managed Redis with Entra ID (access keys off) | **52/52** |

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Tier 1: latest result

```bash
scripts/azure-setup-test-principal.sh      # once, as a subscription Owner
scripts/azure-real-cloud.sh                # AZURE_LOCATION=australiaeast by default
IT_TEST='RealAzureIT$Cosmos' scripts/azure-real-cloud.sh     # one suite only
```

**Identity.** Terraform and the tests act as a dedicated `halyx-test` service principal. Its
credentials live in `~/.azure/halyx-test.env`, outside the repository and readable only by its owner.
The setup script:
- registers the resource providers
- creates the principal with Contributor on the subscription
- gives it Role Based Access Control Administrator with a condition that lets it assign and remove only
  four data-plane roles

The tests reach every service through `DefaultAzureCredential`'s environment-variable path, as a deployed
application would.

**Provision.** Terraform (`infra/azure/tier1`) creates a resource group of the run's own, tagged
`halyx-test=<run id>`, with:
- a StorageV2 account
- a Standard Service Bus namespace
- a serverless Cosmos DB account with the `items` and `items-sorted` containers
- a Key Vault in RBAC mode
- a free App Configuration store

Shared keys and local auth are **off everywhere**, so only Entra ID works. The principal gets Storage Blob
Data Contributor, Service Bus Data Owner, Key Vault Secrets Officer, App Configuration Data Owner, and Cosmos
DB's built-in Data Contributor. The script waits until the blob role reaches the data plane (one attempt
this run) and gives the rest 30 seconds more.

**Test.** `RealAzureIT` creates Service Bus topics (session-enabled with duplicate detection for the
ordering, deduplication and delay tests), subscriptions with a 5-second lock, and a queue.

**Tear down.** `terraform destroy` deletes the resource group and purges the Key Vault and App
Configuration store rather than leaving them soft-deleted. A check then lists resource groups, tagged
resources, and deleted vaults and stores; a failed listing is reported as `CHECK FAILED`.

| | |
|---|---|
| Date | 2026-10-11, 09:50–10:11 UTC (21 min: provisioning about 6 min, tests about 7 min, teardown about 8 min) |
| Run id | `r26101120508d84` |
| Location | `australiaeast` |
| Result | **BUILD SUCCESS**: 156 passed, 0 failed, 2 skipped (expected) |
| Teardown | resource group destroyed, Key Vault and App Configuration store purged; check: **nothing left** |

| Service | Halyx API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| Blob Storage | `BlobStore` | 75 | 74 | 1 | 201.5s |
| Cosmos DB | `DocumentStore` | 23 | 23 | 0 | 26.8s |
| Service Bus | `Publisher` / `Subscription` | 16 | 16 | 0 | 128.8s |
| Key Vault | `ParameterStore` | 22 | 22 | 0 | 55.1s |
| App Configuration | `ParameterStore` | 22 | 21 | 1 | 18.1s |
| **Total** | | **158** | **156** | **2** | |

Skipped tests, both expected:

- **Blob `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't presign;
  Azure can.
- **App Configuration `earlierVersionsCanBeRead`.** App Configuration keeps no readable history of a key,
  so the driver doesn't declare `VERSIONS`.

Service Bus passes every messaging test, including the ones other providers skip: duplicate detection
and delayed delivery.

**Key Vault timing.** In this run the driver still waited for Key Vault's long-running delete and
recovery operations, so `deletedNamesCanBeReused` took 5.0s inside the API. The waits have since been
removed (see the findings and the run history).

### Per-test results

<details>
<summary>Blob Storage — BlobStore: 74/75 passed, 201.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 0.30 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.11 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.11 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.33 |
| `copyMissingSourceThrowsNotFound` | passed | 0.07 |
| `copyPreservesHttpHeaders` | passed | 0.28 |
| `defaultTierIsHot` | passed | 0.14 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 0.53 |
| `deleteAllSpansSeveralProviderBatches` | passed | 16.71 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.21 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.20 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.22 |
| `deleteMissingBlobIsIdempotent` | passed | 0.06 |
| `deleteRemovesBlob` | passed | 0.21 |
| `downloadWritesCompleteFile(Path)` | passed | 3.62 |
| `emptyBlobRoundTrips` | passed | 0.15 |
| `failedWriteKeepsExistingBlob` | passed | 7.09 |
| `failedWriteStoresNothing` | passed | 5.42 |
| `getMissingBlobThrowsNotFound` | passed | 0.07 |
| `getReturnsMetadataWithContent` | passed | 0.11 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.20 |
| `headMissingBlobReturnsEmpty` | passed | 0.11 |
| `headReturnsSizeAndLastModified` | passed | 0.10 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.17 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.29 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.14 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.18 |
| `listAllStreamsEveryBlob` | passed | 0.58 |
| `listEmptyPrefixReturnsNothing` | passed | 0.06 |
| `listFiltersByPrefix` | passed | 0.23 |
| `listPaginatesInKeyOrder` | passed | 2.78 |
| `listSummariesCarrySize` | passed | 0.13 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 0.48 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 0.58 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.11 |
| `openEndedRangeReadsToEnd` | passed | 0.12 |
| `parallelReadReassemblesRangesInOrder` | passed | 7.64 |
| `parallelReadValidatesChecksum` | passed | 4.57 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 0.33 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 14.49 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.37 |
| `presignedUploadUrlUploadsBlob` | passed | 0.26 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 0.68 |
| `presignedUrlReportsExpiry` | passed | 0.07 |
| `putFromFile(Path)` | passed | 0.14 |
| `putFromStream` | passed | 0.13 |
| `putLargeFile(Path)` | passed | 13.25 |
| `putOverwritesExistingBlob` | passed | 0.23 |
| `putReturnsETagMatchingHead` | passed | 0.15 |
| `putSmallUnknownLengthStream` | passed | 0.12 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.12 |
| `putStoresStandardHttpHeaders` | passed | 0.14 |
| `putThenGetRoundTripsContent` | passed | 0.11 |
| `putUnknownLengthStream` | passed | 10.13 |
| `putWithTierStoresTier` | passed | 0.24 |
| `rangeReadReturnsSlice` | passed | 0.14 |
| `setTierChangesTierButNotContent` | passed | 0.28 |
| `setTierToCurrentTierIsANoOp` | passed | 0.42 |
| `storedChecksumsMatchTheContent` | passed | 0.10 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.40 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.06 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.44 |
| `updateMetadataWithStaleETagFails` | passed | 0.20 |
| `validatedFileAndStreamUploads(Path)` | passed | 1.12 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 25.08 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.19 |
| `validatedWriteInParts` | passed | 16.09 |
| `writeEmptyBlob` | passed | 0.12 |
| `writeExactMultipleOfPartSize` | passed | 21.51 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 7.05 |
| `writeLargeBlobInParts` | passed | 32.76 |
| `writeSmallBlobInOneRequest` | passed | 0.16 |

</details>

<details>
<summary>Cosmos DB — DocumentStore: 23/23 passed, 26.8s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.39 |
| `concurrentIncrementsAreNotLost` | passed | 1.45 |
| `decimalsRoundTripAsNumbers` | passed | 0.15 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.47 |
| `expiredItemsAreNeverReturned` | passed | 3.85 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 1.09 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.99 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.40 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 7.42 |
| `indexQueryPagesThroughManyMatches` | passed | 1.58 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.56 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.66 |
| `missingItemIsEmpty` | passed | 0.26 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.37 |
| `putReplacesTheWholeItem` | passed | 0.20 |
| `queryPagesThroughLargePartitions` | passed | 4.22 |
| `queryReturnsAPartitionInSortOrder` | passed | 1.01 |
| `transactionAppliesEveryWriteTogether` | passed | 0.66 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.46 |
| `updateSetsRemovesAndIncrements` | passed | 0.20 |
| `versionsGuardConcurrentWrites` | passed | 0.41 |

</details>

<details>
<summary>Service Bus — messaging: 16/16 passed, 128.8s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 9.69 |
| `binaryBodiesArriveUnchanged` | passed | 2.13 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 4.10 |
| `delayedMessageIsHeldBack` | passed | 5.07 |
| `extendedLeaseKeepsMessageHidden` | passed | 61.84 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 12.06 |
| `listenerLimitsConcurrency` | passed | 3.50 |
| `nackedMessageIsRedelivered` | passed | 2.09 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 4.09 |
| `publishAllDeliversEveryMessage` | passed | 6.49 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 1.88 |
| `queuesWorkPointToPoint` | passed | 1.39 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 4.99 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 1.38 |
| `settlingTwiceIsRejected` | passed | 1.49 |
| `unsettledMessageReturnsAfterItsLease` | passed | 6.66 |

</details>

<details>
<summary>Key Vault — ParameterStore: 22/22 passed, 55.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 2.27 |
| `deleteIsIdempotent` | passed | 1.95 |
| `deletedNamesCanBeReused` | passed | 5.00 |
| `earlierVersionsCanBeRead` | passed | 2.16 |
| `getAllDoesNotMatchPartialSegments` | passed | 5.32 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 6.99 |
| `invalidNamesAreRejected` | passed | 0.29 |
| `jsonFieldsCanBeRead` | passed | 2.38 |
| `largeValues` | passed | 1.75 |
| `missingIsEmptyAndRequireFails` | passed | 0.61 |
| `nativeNamesAreUsedAsTheyAre` | passed | 2.37 |
| `plainValuesHaveNoFields` | passed | 1.86 |
| `putReplacesWithANewVersion` | passed | 1.96 |
| `putThenGet` | passed | 2.12 |
| `reportsKindAndProvider` | passed | 0.22 |
| `statisticsCountCacheHits` | passed | 1.89 |
| `storesAreIsolatedByPrefix` | passed | 2.73 |
| `typedReads` | passed | 1.36 |
| `valuesKeepSpecialCharactersAndLines` | passed | 1.54 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 5.16 |
| `watchedChangesInvalidateTheCache` | passed | 3.22 |
| `writesInvalidateTheirOwnCache` | passed | 1.97 |

</details>

<details>
<summary>App Configuration — ParameterStore: 21/22 passed, 18.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.89 |
| `deleteIsIdempotent` | passed | 0.89 |
| `deletedNamesCanBeReused` | passed | 0.91 |
| `earlierVersionsCanBeRead` | skipped | 0.00 |
| `getAllDoesNotMatchPartialSegments` | passed | 0.98 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 1.35 |
| `invalidNamesAreRejected` | passed | 0.17 |
| `jsonFieldsCanBeRead` | passed | 0.98 |
| `largeValues` | passed | 0.83 |
| `missingIsEmptyAndRequireFails` | passed | 0.55 |
| `nativeNamesAreUsedAsTheyAre` | passed | 0.73 |
| `plainValuesHaveNoFields` | passed | 0.56 |
| `putReplacesWithANewVersion` | passed | 0.73 |
| `putThenGet` | passed | 0.79 |
| `reportsKindAndProvider` | passed | 0.19 |
| `statisticsCountCacheHits` | passed | 0.58 |
| `storesAreIsolatedByPrefix` | passed | 0.89 |
| `typedReads` | passed | 0.71 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.55 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 2.94 |
| `watchedChangesInvalidateTheCache` | passed | 1.37 |
| `writesInvalidateTheirOwnCache` | passed | 0.50 |

</details>

## Tier 2: latest result

```bash
TIER=2 scripts/azure-real-cloud.sh
```

The script first registers `Microsoft.DBforPostgreSQL` and `Microsoft.Sql`, as the service principal
through the REST API. Terraform (`infra/azure/tier2`) then creates:
- **PostgreSQL:** a flexible server (16, `B_Standard_B1ms`) with Entra and password auth. The service
  principal is its Entra admin (role `halyx-test`), and a password admin `halyx_admin` gets a password
  generated for the run and passed through the environment only.
- **Azure SQL:** a Basic database on a server that allows **Entra ID only**, with the service principal as
  its admin. SQL logins are refused.

Both accept only the test machine's address. Teardown deletes the Terraform state, which held the password.

**MySQL is not run.** An Entra admin on Azure Database for MySQL needs a managed identity that can read the
directory (Graph `User.Read.All`, `GroupMember.Read.All` and `Application.Read.All`, or Directory Readers),
which only a directory admin can grant. Left out by choice for now.

| | |
|---|---|
| Date | 2026-10-11, 11:36–11:49 UTC (13 min: PostgreSQL ready in 6m 4s, Azure SQL in under 4 min, tests 45s) |
| Run id | `r26101122362e30` |
| Engines | PostgreSQL 16 (flexible server), Azure SQL Database (Basic) |
| Result | **BUILD SUCCESS**: 11 passed, 0 failed |
| Teardown | resource group destroyed; state deleted; check: **nothing left** |

| Test | PostgreSQL | Azure SQL |
|---|---|---|
| `loginLatency`: runs first; a warm Entra login must take under 3s | passed (6.0s) | passed (2.0s) |
| `entraLoginRunsQueriesOverTls`: the server reports the session encrypted (`pg_stat_ssl` / `sys.dm_exec_connections.encrypt_option`) | passed (1.0s) | passed (0.9s) |
| `replacedConnectionsLogInAgainWithTokens` | passed (5.2s) | passed (4.5s) |
| `entraLoginIsThePrincipalsRole`: `current_user` is `halyx-test` | passed (9.4s) | — |
| `passwordLoginRunsQueriesOverTls`: the password admin, over TLS | passed (2.5s) | — |
| `tokenDoesNotLogInARoleThatIsNotAnEntraPrincipal`: a token for `halyx_admin` is refused | passed (6.8s) | — |
| `wrongPasswordDoesNotLogIn` | passed (3.4s) | — |
| `sqlPasswordLoginIsRefusedOnAnEntraOnlyServer` | — | passed (2.5s) |

On Azure SQL the service principal's session runs as `<application id>@<tenant id>`
(`SUSER_SNAME()`). The token is the driver's `accessToken` property, with no username.

| Entra login, per new connection | PostgreSQL | Azure SQL |
|---|---|---|
| first on the new server | 4,391 ms | 1,026 ms |
| then | 343–366 ms | 300–316 ms |

## Tier 3: latest result

```bash
TIER=3 scripts/azure-real-cloud.sh
```

The script registers `Microsoft.Cache`, then Terraform (`infra/azure/tier3`) creates an **Azure Managed
Redis** cache (`Balanced_B0`, no high availability) with:
- **access keys off**, so only Entra ID works
- the `EnterpriseCluster` clustering policy: one endpoint, with no redirects to node addresses
- encrypted connections on the public endpoint (port 10000)

The service principal gets the cache's `default` access policy. This subscription doesn't offer classic
Azure Cache for Redis in Australia East, and Managed Redis is its successor. The username is the
principal's object id, which Halyx reads from the token's `oid` claim.

| | |
|---|---|
| Date | 2026-10-11, 11:24–11:40 UTC (16 min: cache ready in 7m 27s, a 60s wait for the access policy, tests 15s) |
| Run id | `r2610112224c642` |
| Result | **BUILD SUCCESS**: 52 passed, 0 failed, on the first run |
| Teardown | resource group destroyed; check: **nothing left** |

| Test | Result |
|---|---|
| Cache conformance kit, with Entra tokens over TLS (public certificate, full host name checks) | **49/49 passed** (11.2s) |
| `entraTokenReadsAndWrites` | passed |
| `unauthenticatedConnectionIsRejected` | passed |
| `forgedTokenIsRejected`: an unsigned token is refused | passed |

## Run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r26101120222893` | tier 1 | 150 passed, 1 failed, 6 errors, 2 skipped | clean (checked by hand) | Cosmos: patch returned no document (`NullPointerException`), and ids escaped with `%` couldn't be read back. Blob: `InvalidMetadata` for the name `schema-version`. The final check failed outright: the script passed `--subscription` before the `az` command. It reported `CHECK FAILED` instead of "nothing left", and a manual check found nothing. |
| `r26101120508d84` | tier 1 | **156/158, 2 skipped** | clean | Latest result, above. |
| `r26101121415f6f` | tier 1, Key Vault | **22/22** | clean | Key Vault waits removed from the driver; the kit retries via `deleteLag()`. `deletedNamesCanBeReused` took 4.2s. One transient error on a pooled connection to the vault made `missingIsEmptyAndRequireFails` take 43.8s while the SDK retried (0.6s in the run before). |
| `r26101122061ed2` | tier 2 | not run | clean | Creating the PostgreSQL flexible server failed with `InternalServerError` (no detail) on Azure's side; the subscription isn't restricted (`OfferRestricted` is disabled, Burstable available). Retried unchanged. |
| `r2610112224c642` | tier 3 | **52/52** | clean | Latest tier 3 result, above, on the first run. |
| `r26101122362e30` | tier 2 | **11/11** | clean | Latest tier 2 result, above. The same configuration succeeded on retry. |

## What real Azure found

The emulators (Floci-az and Microsoft's Service Bus emulator) missed all of these:

| Finding | Fix |
|---|---|
| Real Cosmos returns no document from a write unless asked (`contentResponseOnWriteEnabled` is off in the Java SDK), so `update` failed after the patch had already applied | Patch asks for the document back |
| Cosmos ids can't contain `/ \ ? #`, and the gateway URL-decodes ids in point reads, so the driver's `%` escapes were undone (`order%232` was looked up as `order#2`) | Ids are escaped with `~` (`~23`), and `%` itself is escaped too. Ids written by earlier versions with `%` escapes would need migrating; there's no release yet |
| Azure metadata names must be C# identifiers; `schema-version` is a 400 `InvalidMetadata`. Halyx documented "letters, digits and '-'" as portable | The portable form is now lower-case letters, digits and `_`, starting with a letter. The Azure driver rejects other names with a clear `InvalidArgumentException` before sending anything |
| Azure SQL's Entra-only servers refuse every SQL login, and an Entra admin can be a service principal, whose session runs as `<application id>@<tenant id>` | Nothing to change: `SqlAuth.iam()` sends the token as `accessToken`, with no username |
| Key Vault deletes and recoveries are long-running operations, and the driver waited for them (about 2–5s per call) | Removed: `delete` starts the soft delete and returns. Writing a name that is soft-deleted, or still being deleted or recovered, throws the retryable `UnavailableException`, and starts recovery if the name was soft-deleted. Applications retry; the kit does so through `deleteLag()` |

## IAM

| Who | Role | Scope | Why |
|---|---|---|---|
| `halyx-test` service principal | Contributor | subscription | create and delete each run's resource group and its resources |
| | Role Based Access Control Administrator, with a condition | subscription | assign and remove only Storage Blob Data Contributor, Azure Service Bus Data Owner, Key Vault Secrets Officer and App Configuration Data Owner |
| | Storage Blob Data Contributor, Azure Service Bus Data Owner, Key Vault Secrets Officer, App Configuration Data Owner | each run's resources (Terraform) | the data planes the tests use |
| | Cosmos DB Built-in Data Contributor (Cosmos RBAC) | each run's Cosmos account (Terraform) | Cosmos' own data-plane roles |
| | Entra admin (tier 2) | each run's PostgreSQL server and Azure SQL server (Terraform) | the database logins under test |
| | `default` access policy (tier 3) | each run's Managed Redis cache (Terraform) | the cache's data plane |

Tiers 2 and 3 need nothing beyond tier 1's setup: Contributor covers registering their resource providers,
creating the servers and caches, and naming the principal as their Entra admin or access-policy holder.
