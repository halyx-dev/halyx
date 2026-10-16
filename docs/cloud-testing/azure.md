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
| [1](#tier-1-latest-result) | Blob Storage, Cosmos DB, Service Bus, Key Vault, App Configuration (Entra ID only); Spring Data repositories and Spring Boot on Cosmos DB | **166/168** passed, 2 skipped (expected); Spring **2/2** |
| [2](#tier-2-latest-result) | Azure Database for PostgreSQL and Azure SQL with Entra ID login | **11/11** |
| [3](#tier-3-latest-result) | Azure Managed Redis with Entra ID (access keys off) | **52/52** |
| [4](#tier-4-latest-result) | Event Hubs through its Kafka endpoint, with Entra ID (shared access keys off), plus Spring Boot 3 and 4 | **24/25** passed, 1 skipped (expected); Spring **2/2** |

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
| Date | 2026-10-16, 01:47–02:08 UTC (21 min) |
| Run id | `r26101612477b26` |
| Location | `australiaeast` |
| Result | **BUILD SUCCESS**: 166 passed, 0 failed, 2 skipped (expected); Spring Boot repository test passed on Boot 3 and 4 |
| Teardown | resource group destroyed, Key Vault and App Configuration store purged; check: **nothing left** |

| Service | Halyx API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| Blob Storage | `BlobStore` | 75 | 74 | 1 | 138.2s |
| Cosmos DB | `DocumentStore` | 23 | 23 | 0 | 18.3s |
| Cosmos DB | Spring Data repositories | 10 | 10 | 0 | 14.0s |
| Service Bus | `Publisher` / `Subscription` | 16 | 16 | 0 | 128.5s |
| Key Vault | `ParameterStore` | 22 | 22 | 0 | 29.9s |
| App Configuration | `ParameterStore` | 22 | 21 | 1 | 16.5s |
| **Total** | | **168** | **166** | **2** | |
| Cosmos DB, Spring Boot 3.5 | Spring Boot repository test | 1 | 1 | | |
| Cosmos DB, Spring Boot 4 | Spring Boot repository test | 1 | 1 | | |

Skipped tests, both expected:

- **Blob `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't presign;
  Azure can.
- **App Configuration `earlierVersionsCanBeRead`.** App Configuration keeps no readable history of a key,
  so the driver doesn't declare `VERSIONS`.

Service Bus passes every messaging test, including the ones other providers skip: duplicate detection
and delayed delivery.

**Key Vault timing.** The driver no longer waits for Key Vault's long-running deletes: `deletedNamesCanBeReused`
took 3.5s, the kit retrying while the name was still being deleted (see the findings).

The repository kit and the Spring Boot repository test run on the `items` and `items-sorted` containers,
through `halyx-spring-data-document`. Cosmos DB needs no index beyond its default indexing.

### Per-test results

<details>
<summary>Blob Storage — BlobStore: 74/75 passed, 138.2s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 0.27 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.12 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.14 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.37 |
| `copyMissingSourceThrowsNotFound` | passed | 0.05 |
| `copyPreservesHttpHeaders` | passed | 0.35 |
| `defaultTierIsHot` | passed | 0.12 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 0.45 |
| `deleteAllSpansSeveralProviderBatches` | passed | 17.64 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.19 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.20 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.21 |
| `deleteMissingBlobIsIdempotent` | passed | 0.06 |
| `deleteRemovesBlob` | passed | 0.26 |
| `downloadWritesCompleteFile(Path)` | passed | 3.98 |
| `emptyBlobRoundTrips` | passed | 0.17 |
| `failedWriteKeepsExistingBlob` | passed | 3.49 |
| `failedWriteStoresNothing` | passed | 4.77 |
| `getMissingBlobThrowsNotFound` | passed | 0.06 |
| `getReturnsMetadataWithContent` | passed | 0.15 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.17 |
| `headMissingBlobReturnsEmpty` | passed | 0.11 |
| `headReturnsSizeAndLastModified` | passed | 0.13 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.19 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.26 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.11 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.20 |
| `listAllStreamsEveryBlob` | passed | 0.59 |
| `listEmptyPrefixReturnsNothing` | passed | 0.07 |
| `listFiltersByPrefix` | passed | 0.28 |
| `listPaginatesInKeyOrder` | passed | 2.60 |
| `listSummariesCarrySize` | passed | 0.10 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 0.42 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 0.49 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.11 |
| `openEndedRangeReadsToEnd` | passed | 0.10 |
| `parallelReadReassemblesRangesInOrder` | passed | 5.68 |
| `parallelReadValidatesChecksum` | passed | 3.76 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 0.32 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 14.18 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.34 |
| `presignedUploadUrlUploadsBlob` | passed | 0.30 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 0.64 |
| `presignedUrlReportsExpiry` | passed | 0.05 |
| `putFromFile(Path)` | passed | 0.12 |
| `putFromStream` | passed | 0.13 |
| `putLargeFile(Path)` | passed | 14.40 |
| `putOverwritesExistingBlob` | passed | 0.16 |
| `putReturnsETagMatchingHead` | passed | 0.12 |
| `putSmallUnknownLengthStream` | passed | 0.17 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.14 |
| `putStoresStandardHttpHeaders` | passed | 0.11 |
| `putThenGetRoundTripsContent` | passed | 0.17 |
| `putUnknownLengthStream` | passed | 8.12 |
| `putWithTierStoresTier` | passed | 0.19 |
| `rangeReadReturnsSlice` | passed | 0.13 |
| `setTierChangesTierButNotContent` | passed | 0.28 |
| `setTierToCurrentTierIsANoOp` | passed | 0.35 |
| `storedChecksumsMatchTheContent` | passed | 0.11 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.37 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.05 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.54 |
| `updateMetadataWithStaleETagFails` | passed | 0.15 |
| `validatedFileAndStreamUploads(Path)` | passed | 1.21 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 6.79 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.16 |
| `validatedWriteInParts` | passed | 7.47 |
| `writeEmptyBlob` | passed | 0.11 |
| `writeExactMultipleOfPartSize` | passed | 16.03 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 7.83 |
| `writeLargeBlobInParts` | passed | 8.31 |
| `writeSmallBlobInOneRequest` | passed | 0.20 |

</details>

<details>
<summary>Cosmos DB — DocumentStore: 23/23 passed, 18.3s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.45 |
| `concurrentIncrementsAreNotLost` | passed | 1.15 |
| `decimalsRoundTripAsNumbers` | passed | 0.18 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.56 |
| `expiredItemsAreNeverReturned` | passed | 3.69 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 0.60 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.83 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.40 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 1.14 |
| `indexQueryPagesThroughManyMatches` | passed | 1.44 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.31 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.78 |
| `missingItemIsEmpty` | passed | 0.24 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.19 |
| `putReplacesTheWholeItem` | passed | 0.20 |
| `queryPagesThroughLargePartitions` | passed | 3.31 |
| `queryReturnsAPartitionInSortOrder` | passed | 1.08 |
| `transactionAppliesEveryWriteTogether` | passed | 0.59 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.49 |
| `updateSetsRemovesAndIncrements` | passed | 0.23 |
| `versionsGuardConcurrentWrites` | passed | 0.44 |

</details>

<details>
<summary>Cosmos DB — Spring Data repositories: 10/10 passed, 14.0s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `indexQueriesAcrossPartitions` | passed | 1.18 |
| `indexWithoutASortAttribute` | passed | 0.63 |
| `partitionQueriesInSortKeyOrderEitherWay` | passed | 0.90 |
| `savedEntitiesReadBackWithTheirVersion` | passed | 0.26 |
| `sortKeyConditions` | passed | 1.41 |
| `staleVersionsAndTakenIdsAreRejected` | passed | 7.33 |
| `streamsReadLazily` | passed | 0.37 |
| `unversionedEntitiesAreSavedInBatchesAndOverwritten` | passed | 0.66 |
| `uuidKeysAndStringIds` | passed | 0.35 |
| `windowsScrollThroughAPartition` | passed | 0.86 |

</details>

<details>
<summary>Service Bus — Publisher / Subscription: 16/16 passed, 128.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 9.67 |
| `binaryBodiesArriveUnchanged` | passed | 1.64 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 3.81 |
| `delayedMessageIsHeldBack` | passed | 5.31 |
| `extendedLeaseKeepsMessageHidden` | passed | 61.91 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 12.23 |
| `listenerLimitsConcurrency` | passed | 3.43 |
| `nackedMessageIsRedelivered` | passed | 1.86 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 3.89 |
| `publishAllDeliversEveryMessage` | passed | 6.93 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 1.65 |
| `queuesWorkPointToPoint` | passed | 1.61 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 4.78 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 1.10 |
| `settlingTwiceIsRejected` | passed | 1.83 |
| `unsettledMessageReturnsAfterItsLease` | passed | 6.86 |

</details>

<details>
<summary>Key Vault — ParameterStore: 22/22 passed, 29.9s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 1.08 |
| `deleteIsIdempotent` | passed | 1.27 |
| `deletedNamesCanBeReused` | passed | 3.50 |
| `earlierVersionsCanBeRead` | passed | 1.02 |
| `getAllDoesNotMatchPartialSegments` | passed | 1.93 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 2.41 |
| `invalidNamesAreRejected` | passed | 0.28 |
| `jsonFieldsCanBeRead` | passed | 1.19 |
| `largeValues` | passed | 0.76 |
| `missingIsEmptyAndRequireFails` | passed | 0.74 |
| `nativeNamesAreUsedAsTheyAre` | passed | 1.21 |
| `plainValuesHaveNoFields` | passed | 0.81 |
| `putReplacesWithANewVersion` | passed | 0.98 |
| `putThenGet` | passed | 1.13 |
| `reportsKindAndProvider` | passed | 0.22 |
| `statisticsCountCacheHits` | passed | 0.96 |
| `storesAreIsolatedByPrefix` | passed | 1.81 |
| `typedReads` | passed | 0.87 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.76 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 3.58 |
| `watchedChangesInvalidateTheCache` | passed | 2.25 |
| `writesInvalidateTheirOwnCache` | passed | 1.16 |

</details>

<details>
<summary>App Configuration — ParameterStore: 21/22 passed, 16.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.62 |
| `deleteIsIdempotent` | passed | 0.90 |
| `deletedNamesCanBeReused` | passed | 0.98 |
| `earlierVersionsCanBeRead` | skipped | 0.00 |
| `getAllDoesNotMatchPartialSegments` | passed | 1.11 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 1.15 |
| `invalidNamesAreRejected` | passed | 0.15 |
| `jsonFieldsCanBeRead` | passed | 0.88 |
| `largeValues` | passed | 0.59 |
| `missingIsEmptyAndRequireFails` | passed | 0.47 |
| `nativeNamesAreUsedAsTheyAre` | passed | 0.57 |
| `plainValuesHaveNoFields` | passed | 0.48 |
| `putReplacesWithANewVersion` | passed | 0.67 |
| `putThenGet` | passed | 0.68 |
| `reportsKindAndProvider` | passed | 0.16 |
| `statisticsCountCacheHits` | passed | 0.50 |
| `storesAreIsolatedByPrefix` | passed | 1.18 |
| `typedReads` | passed | 0.55 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.56 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 2.27 |
| `watchedChangesInvalidateTheCache` | passed | 1.46 |
| `writesInvalidateTheirOwnCache` | passed | 0.55 |

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

## Tier 4: latest result

```bash
TIER=4 scripts/azure-real-cloud.sh
```

The script registers `Microsoft.EventHub`, then Terraform (`infra/azure/tier4`) creates three **Event Hubs**
namespaces (Standard, one throughput unit) with shared access keys off, so only Entra ID works, and makes the
principal their Azure Event Hubs Data Owner. `RealAzureStreamIT` runs the stream kit on the namespaces' Kafka
endpoints (port 9093), logging in with Entra ID tokens through SASL `OAUTHBEARER`. It creates each test's
topics (event hubs) through Kafka's admin API, logged in the same way, and deletes older ones as it goes.
Before each creation it logs how many topics the namespace lists.

After the kit, `RealSpringStreamIT` runs on Spring Boot 3.5 and on Spring Boot 4, with the Spring for Apache
Kafka and Kafka clients each manages, against the namespace the kit used least. In one Spring application it
sends and receives records through the starter's `StreamProducer` bean and `@HalyxListener`, and through Spring
for Apache Kafka's `KafkaTemplate` and `@KafkaListener`, which log in through `halyx.spring-kafka.cluster`. It
creates its two topics with Kafka's admin client, logged in with `kafkaClientProperties`.

`KAFKA_CLIENTS=4.2.1 TIER=4 scripts/azure-real-cloud.sh` runs the same tests on Kafka 4 clients.

Three namespaces, because a Standard namespace refused new event hubs with `PolicyViolationException` after
about twenty had been created in a run, even with only one or two alive (see the run history). The test
moves to the next namespace before one has created twelve.

| | |
|---|---|
| Date | 2026-10-16, 00:44–00:54 UTC (10 min: a 90s wait for the role, kit 3m 38s, Spring tests 33s) |
| Run id | `r2610161144379f` |
| Clients | Kafka 3.9.2 (kit and Boot 3, with Spring for Apache Kafka 3.3.16); Kafka 4.2.1 (Boot 4, with Spring for Apache Kafka 4.1.1) |
| Result | **all passed**: kit 24 passed, 1 skipped; Spring 2 of 2 |
| Teardown | resource group destroyed; check: **nothing left** |

| Test | Result |
|---|---|
| Stream conformance kit, with Entra ID tokens over TLS | **24/25 passed**, 1 skipped (217.7s) |
| `largeValuesTravelThroughAClaimCheck` | skipped: tier 4 creates no blob store |
| `idempotentProducersNeedTheCapability` | passed: Event Hubs accepts idempotent producers, which Halyx now declares and leaves on by default |
| `recordsWithTheSameKeyStayInOrder`, `handlerSeesEveryRecordOfItsPartitionInOrder` | passed with idempotent producers and Kafka's five requests in flight |
| `transactionalSendAllIsVisibleToReadCommittedConsumers` | passed: Halyx doesn't declare transactions on Event Hubs Standard, and refuses them with `UnsupportedFeatureException` |
| `RealSpringStreamIT` on Spring Boot 3.5: starter, `KafkaTemplate` and `@KafkaListener` | **passed** (15.5s) |
| `RealSpringStreamIT` on Spring Boot 4 | **passed** (17.5s) |

The same kit, with idempotence on, also passed 24/25 on Kafka 4.2.1 clients (`r2610161121e9c0`).

Topics per namespace in the passing run: 11, 11 and 4, with one listed before each creation, so deletes do
take effect.

## Run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r26101120222893` | tier 1 | 150 passed, 1 failed, 6 errors, 2 skipped | clean (checked by hand) | Cosmos: patch returned no document (`NullPointerException`), and ids escaped with `%` couldn't be read back. Blob: `InvalidMetadata` for the name `schema-version`. The final check failed outright: the script passed `--subscription` before the `az` command. It reported `CHECK FAILED` instead of "nothing left", and a manual check found nothing. |
| `r26101120508d84` | tier 1 | **156/158, 2 skipped** | clean | |
| `r26101121415f6f` | tier 1, Key Vault | **22/22** | clean | Key Vault waits removed from the driver; the kit retries via `deleteLag()`. `deletedNamesCanBeReused` took 4.2s. One transient error on a pooled connection to the vault made `missingIsEmptyAndRequireFails` take 43.8s while the SDK retried (0.6s in the run before). |
| `r26101122061ed2` | tier 2 | not run | clean | Creating the PostgreSQL flexible server failed with `InternalServerError` (no detail) on Azure's side; the subscription isn't restricted (`OfferRestricted` is disabled, Burstable available). Retried unchanged. |
| `r2610112224c642` | tier 3 | **52/52** | clean | Latest tier 3 result, above, on the first run. |
| `r26101122362e30` | tier 2 | **11/11** | clean | Latest tier 2 result, above. The same configuration succeeded on retry. |
| `r2610121824c199` | tier 4 | not run | clean | Terraform couldn't assign Azure Event Hubs Data Owner: the principal's role-assignment condition allowed only tier 1's four roles. `azure-setup-test-principal.sh` now allows it, and updates the condition on rerun. |
| `r26101218310076` | tier 4 | 18 passed, 2 failed, 4 errors, 1 skipped | clean (the check printed `STILL PRESENT` for a stray newline; checked by hand, and the scripts now ignore whitespace) | Records with the same key arrived out of order: `[7, 10, …, 58, 1, 4]`. The last four topic creations failed with `PolicyViolationException`. `latestStartSkipsEarlierRecords` raced the consumer's first fetch. |
| `r26101218441879` | tier 4 | 21 passed, 1 failed, 2 errors, 1 skipped | clean | Order fixed. A transactional producer failed in Event Hubs' `FindCoordinator` instead of with `UnsupportedFeatureException`. Topic creation still refused after about twenty, with two alive. |
| `r26101219005a2c` | tier 4 | 22 passed, 2 errors, 1 skipped | clean | Three namespaces; nothing refused, and one topic listed before each creation. The test allowed only eight topics per namespace, two short of the kit's 26. |
| `r2610121909d4c7` | tier 4 | **24/25, 1 skipped** | clean | Idempotence off on Event Hubs, as Halyx had it until then. |
| `r2610161043df40` | tier 4, Kafka 3.9 clients, with an idempotence probe | 24/25, 1 skipped; probe passed | clean | The probe sent through Kafka's own producer with idempotence on: Event Hubs accepted it and stored the records in order. |
| `r261016105299da` | tier 4, Kafka 4.2.1 clients | 24/25, 1 skipped; probe error | clean (checked by hand) | The kit passed on Kafka 4 clients. The probe's own settings were wrong: Kafka 4's `linger.ms` defaults to 5, so its `delivery.timeout.ms` of 60000 was below `linger.ms` + `request.timeout.ms`. The check printed `STILL PRESENT` for two namespaces Azure's resource index still listed after their group was gone; it now looks again for up to two minutes. |
| `r2610161112d440` | tier 4 with idempotence on, plus Spring Boot 3 and 4 | kit 24/25, 1 skipped; Spring 0/2 | clean | Idempotence declared and on by default; the ordering tests pass with it. Both Spring tests created their topics through `kafkaClientProperties`, then failed on a SpEL expression in the test's `@KafkaListener`. |
| `r2610161121e9c0` | tier 4, Kafka 4.2.1 clients, idempotence on | **24/25, 1 skipped** | clean | |
| `r2610161144379f` | tier 4, plus Spring Boot 3 and 4 | **24/25, 1 skipped; Spring 2/2** | clean | Latest tier 4 result, above. |
| `r26101612477b26` | tier 1, plus Spring Data repositories and Spring Boot 3 and 4 | **166/168, 2 skipped; Spring 2/2** | clean | Latest tier 1 result, above. Repositories 10/10 on Cosmos DB. |

## What real Azure found

The emulators (Floci-az and Microsoft's Service Bus emulator) missed all of these:

| Finding | Fix |
|---|---|
| Real Cosmos returns no document from a write unless asked (`contentResponseOnWriteEnabled` is off in the Java SDK), so `update` failed after the patch had already applied | Patch asks for the document back |
| Cosmos ids can't contain `/ \ ? #`, and the gateway URL-decodes ids in point reads, so the driver's `%` escapes were undone (`order%232` was looked up as `order#2`) | Ids are escaped with `~` (`~23`), and `%` itself is escaped too. Ids written by earlier versions with `%` escapes would need migrating; there's no release yet |
| Azure metadata names must be C# identifiers; `schema-version` is a 400 `InvalidMetadata`. Halyx documented "letters, digits and '-'" as portable | The portable form is now lower-case letters, digits and `_`, starting with a letter. The Azure driver rejects other names with a clear `InvalidArgumentException` before sending anything |
| Azure SQL's Entra-only servers refuse every SQL login, and an Entra admin can be a service principal, whose session runs as `<application id>@<tenant id>` | Nothing to change: `SqlAuth.iam()` sends the token as `accessToken`, with no username |
| Key Vault deletes and recoveries are long-running operations, and the driver waited for them (about 2–5s per call) | Removed: `delete` starts the soft delete and returns. Writing a name that is soft-deleted, or still being deleted or recovered, throws the retryable `UnavailableException`, and starts recovery if the name was soft-deleted. Applications retry; the kit does so through `deleteLag()` |

Event Hubs' Kafka endpoint found these:

| Finding | Fix |
|---|---|
| Records with the same key arrived out of order. Halyx turned idempotence off on Event Hubs at the time, and Kafka's producer then allows five requests in flight per connection: a batch retried after an error lands behind later ones | Without idempotence, the Kafka driver keeps one request in flight per connection, unless the application sets `kafka.max.in.flight.requests.per.connection` itself |
| A transactional producer on Event Hubs Standard failed in `FindCoordinator` (no transactions), before Halyx could refuse it: the driver started the transaction when the producer was created, ahead of the capability check | Transactional producers start on first use, so `StreamProducerConfig.transactional(true)` without `TRANSACTIONS` fails with `UnsupportedFeatureException` |
| A Standard namespace refused new event hubs with `PolicyViolationException` after about twenty had been created, whether two or four were alive. Deleted event hubs no longer appear in the namespace's topic list. The limit isn't documented, so this is an observation from two runs | Test only: topics are spread over three namespaces. Applications don't create topics through Halyx |
| Halyx turned idempotence off on Event Hubs, where it wasn't confirmed. A Standard namespace accepts Kafka's idempotent producer, and the kit's ordering tests pass with it, on Kafka 3.9 and 4.2 clients | Event Hubs declares `IDEMPOTENCE`, and producers are idempotent by default, as on MSK and Managed Kafka |
| A consumer starting at the latest offset fixes its position on its first fetch, which on Event Hubs came after its partitions were assigned, so a record sent right after assignment could be skipped | Test only: the kit sends until a record arrives. This is how Kafka behaves everywhere, and the API guide says where a new group starts |

## IAM

| Who | Role | Scope | Why |
|---|---|---|---|
| `halyx-test` service principal | Contributor | subscription | create and delete each run's resource group and its resources |
| | Role Based Access Control Administrator, with a condition | subscription | assign and remove only Storage Blob Data Contributor, Azure Service Bus Data Owner, Key Vault Secrets Officer, App Configuration Data Owner and Azure Event Hubs Data Owner |
| | Storage Blob Data Contributor, Azure Service Bus Data Owner, Key Vault Secrets Officer, App Configuration Data Owner | each run's resources (Terraform) | the data planes the tests use |
| | Azure Event Hubs Data Owner (tier 4) | each run's Event Hubs namespaces (Terraform) | sending, receiving, and creating and deleting event hubs through the Kafka endpoint |
| | Cosmos DB Built-in Data Contributor (Cosmos RBAC) | each run's Cosmos account (Terraform) | Cosmos' own data-plane roles |
| | Entra admin (tier 2) | each run's PostgreSQL server and Azure SQL server (Terraform) | the database logins under test |
| | `default` access policy (tier 3) | each run's Managed Redis cache (Terraform) | the cache's data plane |

Tiers 2 and 3 need nothing beyond tier 1's setup: Contributor covers registering their resource providers,
creating the servers and caches, and naming the principal as their Entra admin or access-policy holder.
