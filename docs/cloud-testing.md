# Cloud testing

Every Cirrus driver passes its conformance kit (TCK) against emulators on each build. This file logs runs
of the same kits against **real cloud services**. Those runs catch what emulators don't model, such as IAM,
eventual consistency and asynchronous deletes.

**Summary**

| Cloud | Tier | Services | Latest result |
|---|---|---|---|
| AWS | 1 | S3, DynamoDB, SSM Parameter Store, Secrets Manager, SNS, SQS | **170/172** passed, 2 skipped (expected) |
| AWS | 2 | RDS for PostgreSQL and MySQL with IAM auth | **12/12** |
| AWS | 3 | ElastiCache for Valkey with IAM auth | 52/53: the IAM policy still needs checking |
| GCP | 1 | Cloud Storage, Firestore, Pub/Sub, Secret Manager, Parameter Manager | **176/179** passed, 3 skipped (expected) |
| GCP | 2 | Cloud SQL for PostgreSQL and MySQL with IAM auth, direct and through the Cloud SQL connector | **18/18** |

## How an AWS run works

```bash
AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
# a single suite:
IT_TEST='RealAwsIT$SecretsManager' AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
```

1. **Provision.** Terraform (`infra/aws/tier1`) creates one S3 bucket and two DynamoDB tables. Their names
   include a fresh run id (`r<yyMMddHHmm><hex>`), and everything is tagged `cirrus-test=<run id>`.
2. **Test.** `RealAwsIT` runs the conformance kits, and is enabled only by `-Dcirrus.real.aws=true`.
   The tests create SNS topics, SQS queues, SSM parameters and secrets themselves, then delete them.
3. **Tear down.** This runs on exit, failure or Ctrl-C. A sweep deletes anything the tests left behind:
   - SNS topics and SQS queues named `cirrus-<run>-*`
   - SSM parameters and secrets under `tck-*` created since the run started
     (secrets are deleted without recovery)

   Then `terraform destroy` runs, and a check confirms that nothing tagged with the run id, and none of
   the bucket, tables, topics or queues, is left.

Credentials come from the named AWS profile and never from the repository.

## AWS tier 1: latest result

| | |
|---|---|
| Date | 2026-10-11, 03:22–03:30 UTC (8m 12s including provision and teardown) |
| Run id | `r2610111422284e` |
| Region | `ap-southeast-2` |
| Toolchain | Java 25.0.2, Maven 3.9.14, Terraform 1.5.7, AWS SDK 2.55.14 |
| Result | **BUILD SUCCESS**: 170 passed, 0 failed, 2 skipped (expected) |
| Teardown | 11 leftover secrets swept, stack destroyed, check: **nothing left** |

| Service | Cirrus API | Tests | Passed | Skipped | Time |
|---|---|---|---|---|---|
| S3 | `BlobStore` | 75 | 74 | 1 | 205.3s |
| DynamoDB | `DocumentStore` | 23 | 23 | 0 | 10.1s |
| SSM Parameter Store | `ParameterStore` | 22 | 22 | 0 | 13.5s |
| Secrets Manager | `ParameterStore` | 22 | 22 | 0 | 35.6s |
| SNS → SQS | `Publisher` / `Subscription` | 15 | 14 | 1 | 86.4s |
| SQS queue | `Publisher` / `Subscription` | 15 | 15 | 0 | 33.5s |
| **Total** | | **172** | **170** | **2** | |

Skipped tests, both expected:

- **S3 `presignWithoutCapabilityIsRejected`.** This test only applies to providers that can't presign;
  S3 can.
- **SNS → SQS `delayedMessageIsHeldBack`.** SNS has no per-message delay, so the driver doesn't declare
  `DELAYED_DELIVERY`. A plain SQS queue does support it, and that test passes in the SQS suite.

### Per-test results

<details>
<summary>S3 — BlobStore: 74/75 passed, 205.2s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `abortedPresignedUploadCreatesNoBlob` | passed | 0.48 |
| `closingTheStreamInsideWriteDoesNotCommitEarly` | passed | 0.14 |
| `contentEncodingIsStoredAndBytesAreReturnedUnchanged` | passed | 0.15 |
| `copyDuplicatesContentAndKeepsSource` | passed | 0.28 |
| `copyMissingSourceThrowsNotFound` | passed | 0.07 |
| `copyPreservesHttpHeaders` | passed | 0.18 |
| `defaultTierIsHot` | passed | 0.11 |
| `deleteAllRemovesEveryKeyIncludingMissingOnes` | passed | 0.55 |
| `deleteAllSpansSeveralProviderBatches` | passed | 18.28 |
| `deleteAllWithNoKeys` | passed | 0.00 |
| `deleteIfMatchOnMissingBlobFails` | passed | 0.24 |
| `deleteIfMatchWithCurrentETagDeletes` | passed | 0.21 |
| `deleteIfMatchWithStaleETagFails` | passed | 0.27 |
| `deleteMissingBlobIsIdempotent` | passed | 0.07 |
| `deleteRemovesBlob` | passed | 0.19 |
| `downloadWritesCompleteFile(Path)` | passed | 3.65 |
| `emptyBlobRoundTrips` | passed | 0.23 |
| `failedWriteKeepsExistingBlob` | passed | 4.66 |
| `failedWriteStoresNothing` | passed | 3.96 |
| `getMissingBlobThrowsNotFound` | passed | 0.05 |
| `getReturnsMetadataWithContent` | passed | 0.15 |
| `getWithStaleETagThrowsPreconditionFailed` | passed | 0.18 |
| `headMissingBlobReturnsEmpty` | passed | 0.14 |
| `headReturnsSizeAndLastModified` | passed | 0.15 |
| `ifMatchWithCurrentETagSucceeds` | passed | 0.21 |
| `ifMatchWithStaleETagThrowsPreconditionFailed` | passed | 0.27 |
| `ifNotExistsCreatesMissingBlob` | passed | 0.24 |
| `ifNotExistsRejectsExistingBlob` | passed | 0.20 |
| `listAllStreamsEveryBlob` | passed | 0.60 |
| `listEmptyPrefixReturnsNothing` | passed | 0.05 |
| `listFiltersByPrefix` | passed | 0.29 |
| `listPaginatesInKeyOrder` | passed | 1.54 |
| `listSummariesCarrySize` | passed | 0.11 |
| `listWithDelimiterGroupsKeysIntoPrefixes` | passed | 0.44 |
| `listWithDelimiterPaginatesOverItemsAndPrefixes` | passed | 0.59 |
| `listWithoutDelimiterReturnsNoPrefixes` | passed | 0.13 |
| `openEndedRangeReadsToEnd` | passed | 0.12 |
| `parallelReadReassemblesRangesInOrder` | passed | 7.55 |
| `parallelReadValidatesChecksum` | passed | 5.37 |
| `presignWithoutCapabilityIsRejected` | skipped | 0.00 |
| `presignedDownloadUrlDownloadsBlob` | passed | 0.79 |
| `presignedMultipartUploadAssemblesPartsInOrder` | passed | 17.83 |
| `presignedUploadUrlReplacesExistingBlob` | passed | 0.30 |
| `presignedUploadUrlUploadsBlob` | passed | 0.25 |
| `presignedUploadWithContentTypeSetsContentType` | passed | 0.36 |
| `presignedUrlReportsExpiry` | passed | 0.01 |
| `putFromFile(Path)` | passed | 0.13 |
| `putFromStream` | passed | 0.15 |
| `putLargeFile(Path)` | passed | 17.59 |
| `putOverwritesExistingBlob` | passed | 0.18 |
| `putReturnsETagMatchingHead` | passed | 0.12 |
| `putSmallUnknownLengthStream` | passed | 0.14 |
| `putStoresContentTypeAndUserMetadata` | passed | 0.11 |
| `putStoresStandardHttpHeaders` | passed | 0.14 |
| `putThenGetRoundTripsContent` | passed | 0.15 |
| `putUnknownLengthStream` | passed | 25.17 |
| `putWithTierStoresTier` | passed | 0.22 |
| `rangeReadReturnsSlice` | passed | 0.13 |
| `setTierChangesTierButNotContent` | passed | 0.40 |
| `setTierToCurrentTierIsANoOp` | passed | 0.44 |
| `storedChecksumsMatchTheContent` | passed | 0.13 |
| `unwrapUnknownTypeThrows` | passed | 0.00 |
| `updateMetadataKeepsTier` | passed | 0.30 |
| `updateMetadataOfMissingBlobThrowsNotFound` | passed | 0.07 |
| `updateMetadataReplacesHeadersAndMetadataButNotContent` | passed | 0.42 |
| `updateMetadataWithStaleETagFails` | passed | 0.26 |
| `validatedFileAndStreamUploads(Path)` | passed | 1.28 |
| `validatedLargeFileUploadRoundTrips(Path)` | passed | 19.82 |
| `validatedPutStoresAChecksumThatValidatesOnDownload` | passed | 0.20 |
| `validatedWriteInParts` | passed | 14.36 |
| `writeEmptyBlob` | passed | 0.14 |
| `writeExactMultipleOfPartSize` | passed | 17.66 |
| `writeInPartsWithIfNotExistsRejectsExistingBlob` | passed | 7.89 |
| `writeLargeBlobInParts` | passed | 25.78 |
| `writeSmallBlobInOneRequest` | passed | 0.20 |

</details>

<details>
<summary>DynamoDB — DocumentStore: 23/23 passed, 10.1s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `checksGuardATransactionWithoutWriting` | passed | 0.32 |
| `concurrentIncrementsAreNotLost` | passed | 0.53 |
| `decimalsRoundTripAsNumbers` | passed | 0.11 |
| `deleteReportsWhetherAnItemWasRemoved` | passed | 0.30 |
| `expiredItemsAreNeverReturned` | passed | 3.44 |
| `failedConditionRollsBackTheWholeTransaction` | passed | 0.46 |
| `getAllReturnsWhatExistsAcrossBatches` | passed | 0.53 |
| `ifNotExistsCreatesOnlyOnce` | passed | 0.18 |
| `indexQueryFindsItemsAcrossPartitionsInSortOrder` | passed | 0.54 |
| `indexQueryPagesThroughManyMatches` | passed | 0.24 |
| `indexWithoutSortAttributeFindsEveryMatch` | passed | 0.18 |
| `invalidKeysAndReservedFieldsAreRejected` | passed | 0.00 |
| `keysWithSpecialCharactersRoundTrip` | passed | 0.43 |
| `missingItemIsEmpty` | passed | 0.11 |
| `putAndGetRoundTripsEveryValueType` | passed | 0.11 |
| `putReplacesTheWholeItem` | passed | 0.14 |
| `queryPagesThroughLargePartitions` | passed | 0.84 |
| `queryReturnsAPartitionInSortOrder` | passed | 0.42 |
| `transactionAppliesEveryWriteTogether` | passed | 0.42 |
| `transactionsStayWithinOnePartition` | passed | 0.00 |
| `updateNeedsAnExistingItemAndTheRightVersion` | passed | 0.32 |
| `updateSetsRemovesAndIncrements` | passed | 0.18 |
| `versionsGuardConcurrentWrites` | passed | 0.30 |

</details>

<details>
<summary>SSM Parameter Store — ParameterStore: 22/22 passed, 13.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.56 |
| `deleteIsIdempotent` | passed | 0.46 |
| `deletedNamesCanBeReused` | passed | 0.75 |
| `earlierVersionsCanBeRead` | passed | 0.44 |
| `getAllDoesNotMatchPartialSegments` | passed | 0.79 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 1.09 |
| `invalidNamesAreRejected` | passed | 0.14 |
| `jsonFieldsCanBeRead` | passed | 0.52 |
| `largeValues` | passed | 0.47 |
| `missingIsEmptyAndRequireFails` | passed | 0.38 |
| `nativeNamesAreUsedAsTheyAre` | passed | 0.74 |
| `plainValuesHaveNoFields` | passed | 0.42 |
| `putReplacesWithANewVersion` | passed | 0.49 |
| `putThenGet` | passed | 0.43 |
| `reportsKindAndProvider` | passed | 0.14 |
| `statisticsCountCacheHits` | passed | 0.44 |
| `storesAreIsolatedByPrefix` | passed | 1.01 |
| `typedReads` | passed | 0.42 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.48 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 1.72 |
| `watchedChangesInvalidateTheCache` | passed | 1.00 |
| `writesInvalidateTheirOwnCache` | passed | 0.58 |

</details>

<details>
<summary>Secrets Manager — ParameterStore: 22/22 passed, 35.6s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `cachedReadsAreServedUntilRefresh` | passed | 0.63 |
| `deleteIsIdempotent` | passed | 1.11 |
| `deletedNamesCanBeReused` | passed | 11.91 |
| `earlierVersionsCanBeRead` | passed | 0.49 |
| `getAllDoesNotMatchPartialSegments` | passed | 2.41 |
| `getAllReturnsEverythingBelowAPrefix` | passed | 3.08 |
| `invalidNamesAreRejected` | passed | 0.17 |
| `jsonFieldsCanBeRead` | passed | 0.54 |
| `largeValues` | passed | 0.52 |
| `missingIsEmptyAndRequireFails` | passed | 0.35 |
| `nativeNamesAreUsedAsTheyAre` | passed | 3.13 |
| `plainValuesHaveNoFields` | passed | 0.40 |
| `putReplacesWithANewVersion` | passed | 0.52 |
| `putThenGet` | passed | 0.48 |
| `reportsKindAndProvider` | passed | 0.06 |
| `statisticsCountCacheHits` | passed | 0.39 |
| `storesAreIsolatedByPrefix` | passed | 1.13 |
| `typedReads` | passed | 0.50 |
| `valuesKeepSpecialCharactersAndLines` | passed | 0.44 |
| `watchReportsAddedUpdatedAndRemovedValues` | passed | 4.01 |
| `watchedChangesInvalidateTheCache` | passed | 2.43 |
| `writesInvalidateTheirOwnCache` | passed | 0.87 |

</details>

<details>
<summary>SNS → SQS — messaging: 14/15 passed, 79.2s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 7.78 |
| `binaryBodiesArriveUnchanged` | passed | 4.11 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 5.32 |
| `delayedMessageIsHeldBack` | skipped | 0.00 |
| `extendedLeaseKeepsMessageHidden` | passed | 7.97 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 8.53 |
| `listenerLimitsConcurrency` | passed | 5.58 |
| `nackedMessageIsRedelivered` | passed | 4.29 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 4.83 |
| `publishAllDeliversEveryMessage` | passed | 6.31 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 3.75 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 7.59 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 3.60 |
| `settlingTwiceIsRejected` | passed | 3.71 |
| `unsettledMessageReturnsAfterItsLease` | passed | 5.86 |

</details>

<details>
<summary>SQS queue — messaging: 15/15 passed, 33.5s</summary>

| Test | Result | Time (s) |
|---|---|---|
| `ackedMessageIsNotRedelivered` | passed | 4.53 |
| `binaryBodiesArriveUnchanged` | passed | 0.37 |
| `claimCheckSendsLargeBodiesThroughBlobStorage` | passed | 1.59 |
| `delayedMessageIsHeldBack` | passed | 4.50 |
| `extendedLeaseKeepsMessageHidden` | passed | 4.59 |
| `listenerAcksSuccessesAndRetriesFailures` | passed | 4.86 |
| `listenerLimitsConcurrency` | passed | 2.27 |
| `nackedMessageIsRedelivered` | passed | 0.61 |
| `orderingKeyDeliversEachKeyInOrder` | passed | 0.93 |
| `publishAllDeliversEveryMessage` | passed | 2.55 |
| `publishedMessageArrivesWithBodyAndAttributes` | passed | 0.34 |
| `repeatedDeduplicationIdIsDeliveredOnce` | passed | 3.50 |
| `reservedAttributesAndUnsupportedControlsAreRejected` | passed | 0.14 |
| `settlingTwiceIsRejected` | passed | 0.35 |
| `unsettledMessageReturnsAfterItsLease` | passed | 2.35 |

</details>

## AWS tier 2: latest result

```bash
TIER=2 AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
```

Terraform (`infra/aws/tier2`) creates RDS for PostgreSQL 17 and RDS for MySQL 8.4 instances in the default VPC:
- `db.t4g.micro`, IAM database authentication on, no backups, no final snapshot
- reachable only from the test machine's public IPv4 address (a `/32` security group rule)
- a master password generated for the run and passed through the environment only. Terraform's state
  holds it, and teardown deletes the state once destroy succeeds.

`RealAwsSqlIT` signs in as the master user and creates two database users:
- `cirrus_iam`, enabled for IAM (`GRANT rds_iam` on PostgreSQL, `AWSAuthenticationPlugin` on MySQL)
- `cirrus_password_only`, which is not

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
| `iamTokenLoginRunsQueriesOverTls`: `SqlAuth.iam("cirrus_iam")` runs queries as `cirrus_iam` over TLS, and the pool reports healthy | passed (9.4s) | passed (10.2s) |
| `replacedConnectionsLogInAgainWithTokens`: three rounds of filling the pool, then evicting it, with every new connection signing in by token | passed (9.7s) | passed (8.9s) |
| `tokenDoesNotLogInAUserNotEnabledForIam`: a token for `cirrus_password_only` is rejected, and the pool reports unhealthy | passed (2.6s) | passed (2.5s) |
| `wrongPasswordDoesNotLogIn` | passed (1.4s) | passed (1.6s) |

TLS is checked on the server side: `pg_stat_ssl` for the session on PostgreSQL, and `Ssl_version` on
MySQL. It isn't inferred from the client settings. The per-test times above come from the earlier run
`r2610111453e0bc`, before `loginLatency` existed. Without it, the first IAM test also paid the
first-login cost described below.

### Login latency

Run `r26101115149618` timed every login on newly created instances, in order. It measured plain JDBC with
no Cirrus code in the path, and then a Cirrus IAM pool:

| Login | PostgreSQL | MySQL |
|---|---|---|
| plain JDBC, password (×3) | 327–369 ms | 344–368 ms |
| plain JDBC, **first** token on the instance | **6,120 ms** | **5,202 ms** |
| plain JDBC, later tokens (×4) | 650–945 ms | 791–1,078 ms |
| Cirrus pool, new token connections (×3) | 569–1,327 ms | 518–1,196 ms |
| token signing, client side | 1 ms (122 ms on first use, when credentials resolve) | 0–3 ms |

Findings:

- **Cirrus adds no measurable cost.** Signing is local and takes about 1ms, and the token is cached per
  pool. Pool connections cost the same as plain JDBC with a token.
- **The cost is on the server.** RDS checks each token as the connection logs in. That adds about
  0.3–0.7s per new connection over a password login. The first token login a new instance sees takes
  5–6s.
- **Pools hide the cost.** Only opening a connection pays it. `minIdle > 0` opens connections in the
  background, so the first request doesn't wait.

Earlier, `iamTokenLoginRunsQueriesOverTls` took about 9.4s because it was the first IAM login on the
instance. `replacedConnectionsLogInAgainWithTokens` took about 9s because it opens 12 connections.

## AWS tier 3: latest result

```bash
TIER=3 AWS_PROFILE=default AWS_REGION=ap-southeast-2 scripts/aws-real-cloud.sh
```

Terraform (`infra/aws/tier3`) creates the following in the default VPC:
- **Cache:** an ElastiCache for Valkey 8.0 replication group, one `cache.t4g.micro` node, with TLS in
  transit and encryption at rest.
- **Users:** a user group with the required `default` user, switched off. Valkey users can't be
  password-free, so it gets a random password for each run that nothing uses. There are also two IAM
  users:
  - `cirrus-<run>-app`, which the caller's policy may connect as
  - `cirrus-<run>-denied`, which it may not
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
| Cache conformance kit, logged in as `cirrus-<run>-app` with IAM tokens over verified TLS | **49/49 passed** | 13.5s |
| `iamUserReadsAndWritesOverVerifiedTls` | passed | 0.90s |
| `unauthenticatedConnectionIsRejected` | passed | 0.20s |
| `tokenSignedForAnotherCacheIsRejected`: a token signed for another replication group id | passed | 2.83s |
| `iamUserWithoutConnectPermissionIsRejected` | **failed**: the `-denied` user connected | 0.33s |

This is the first time ElastiCache IAM authentication has been verified end to end. The emulator can't
attach IAM users to a replication group, so until now tokens had only been checked against a SigV4
validator.

**The open failure.** `cirrus-<run>-denied` is a real IAM-enabled user. The test signs the token for that
user and opens its own connection, because connections are keyed by user. ElastiCache accepted it, so the
caller's effective IAM permissions include `elasticache:Connect` on that user. The intended policy, below,
grants it only for `user:cirrus-*-app`. The policy actually attached to `cirrus_test` still needs checking
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

## AWS run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r26101113239811` | tier 1 | 148 passed, 22 errors, 2 skipped | clean | Secrets Manager 0/22: the IAM user had no Secrets Manager permissions. This also exposed that `AwsErrors` mapped a 400 `AccessDeniedException` to an invalid argument instead of access denied. |
| `r26101113365837` | tier 1, Secrets Manager | 14/22 | clean | `BatchGetSecretValue` not yet granted. Writes failed while a deleted name was still being deleted, and listings lagged behind writes. |
| `r2610111345e031` | tier 1, Secrets Manager | 19/22 | clean | A read during a pending delete threw. `BatchGetSecretValue` was still denied right after it was granted, probably because IAM changes take time to propagate. |
| `r26101113510091` | tier 1, Secrets Manager | 22/22 | clean | |
| `r26101113562b9e` | tier 1, Secrets Manager | 22/22 | clean | Confirmed the batch read path with the final policy. |
| `r2610111402665b` | tier 1 | 169 passed, 1 failed, 2 skipped | clean | Secrets Manager `nativeNamesAreUsedAsTheyAre`: `get` returned a secret just after its delete was accepted. |
| `r2610111411c824` | tier 1 | 169 passed, 1 failed, 2 skipped | clean | Secrets Manager `watchedChangesInvalidateTheCache`: the watch saw the new value, but the read after it returned the old one. |
| `r2610111422284e` | tier 1 | **170 passed, 2 skipped** | clean | Latest tier 1 result, above. |
| `r26101114370e5e` | tier 1, Secrets Manager | 22/22 | clean | The waits were removed from the driver. Writing a name that is still being deleted now fails with `UnavailableException`, and the kit retries via `deleteLag()`. `deletedNamesCanBeReused` took 18.2s, which is how long AWS took to finish that delete. |
| `r26101114471b0c` | tier 2 | not run | clean (subnet group deleted) | Stopped at `terraform apply`: no `ec2:CreateSecurityGroup` permission. |
| `r2610111453e0bc` | tier 2 | 10/10 | clean | The first IAM login took about 9s, which led to the latency investigation. |
| `r26101115149618` | tier 2 | **12/12** | clean | Latest tier 2 result. `loginLatency` added: the slow first token login also happens with plain JDBC, so the cost is on the RDS side. |
| `r26101115384945` | tier 1, DynamoDB | 23/23 | clean | DynamoDB batches no longer sleep and resend unprocessed items; they throw `ThrottledException`. |
| `r2610111548cee8` | tier 3 | not run | clean | Stopped at `terraform apply`. The account is on the Free Tier plan, which can't launch `t4g.nano` (bastion moved to `t4g.micro`), and Valkey refuses password-free users (the `default` user now gets a random password). |
| `r26101115542175` | tier 3 | 5/53 | clean | Every connection failed: Lettuce resolved the cache's name with Netty's DNS client, which ignores the JVM hosts file, so nothing went through the tunnel. Led to `redis.dns-resolver=jdk`. The bastion showed in the tagging index after termination, so the check now looks at instances and volumes directly. |
| `r2610111628619f` | tier 3 | **52/53** | clean | Latest tier 3 result, above. The IAM user without `Connect` permission was let in. |

## What real AWS found

Emulators missed all of these:

| Finding | Fix |
|---|---|
| A 400 `AccessDeniedException` (and other auth error codes) was mapped by status to `InvalidArgument` | `AwsErrors` checks auth error codes before status codes, so they map to `AccessDenied` |
| A deleted secret's name can't be reused until AWS finishes the delete ("marked for deletion"; 18s measured) | The write fails with the retryable `UnavailableException`; `delete` treats an in-progress delete as done. Cirrus doesn't wait, so callers retry. The TCK allows for this with `deleteLag()` |
| Reading a secret whose delete is still in progress throws | `get` returns empty |
| `GetSecretValue` can return a secret for a moment after its delete is accepted | Documented as eventual. The TCK allows for it with `deleteLag()`. The API doesn't wait |
| `ListSecrets` lags behind writes | Documented as eventual. The Secrets Manager TCK allows a listing lag (`listingLag()`) |
| A listing can see a new value before `GetSecretValue` does | When a watch reports a change, it caches the values it saw, so reads agree with the event on every provider |
| Lettuce resolves host names with Netty's own DNS client, which ignores JVM DNS settings (`jdk.net.hosts.file`, DNS cache TTLs, custom `InetAddressResolver`s) | New opt-in cache property `redis.dns-resolver=jdk` resolves like the rest of the JVM; the default is unchanged |
| Not every policy grants `BatchGetSecretValue` | If batch reads are denied, `getAll` reads secrets one at a time and logs a warning |

## IAM used for AWS tier 1

The `cirrus_test` user needs the following. Secrets are limited to the `tck-*` names the kits use.

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

## IAM used for AWS tier 2

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
    { "Effect": "Allow", "Action": "rds-db:connect", "Resource": "arn:aws:rds-db:<region>:<account>:dbuser:*/cirrus_iam" },
    {
      "Effect": "Allow",
      "Action": "iam:CreateServiceLinkedRole",
      "Resource": "*",
      "Condition": { "StringEquals": { "iam:AWSServiceName": "rds.amazonaws.com" } }
    }
  ]
}
```

`rds-db:connect` is what the tests exercise. It is granted for `cirrus_iam` only, so the token for
`cirrus_password_only` is refused. `iam:CreateServiceLinkedRole` is needed only the first time an account
uses RDS.

## IAM used for AWS tier 3

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
        "arn:aws:elasticache:<region>:<account>:replicationgroup:cirrus-*",
        "arn:aws:elasticache:<region>:<account>:user:cirrus-*-app"
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

## GCP tier 1: latest result

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

## GCP tier 2: latest result

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

## GCP run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r2610111704a192` | tier 1 | 176 passed, 1 failed, 1 error, 3 skipped | clean (checked by hand) | Pub/Sub `orderingKeyDeliversEachKeyInOrder` and Cloud Storage `updateMetadataWithStaleETagFails` failed (see below). The regional Parameter Manager check failed with a misleading `PERMISSION_DENIED`, and the script took the failure as empty. |
| `r2610111748599e` | tier 1 | **176/179, 3 skipped** | clean | Latest tier 1 result, above. |
| `r26101118357636` | tier 2 | 13/18 | clean | PostgreSQL 9/9. MySQL's five IAM tests logged in, then were refused the `app` database: IAM users have no privileges until granted. |
| `r2610111859cad1` | tier 2 | **18/18** | clean | Latest tier 2 result, above. The test now grants the MySQL IAM user `SELECT` on `app`. |

## What real GCP found

The emulators (Floci-gcp, and Google's Pub/Sub and Firestore emulators) missed all of these:

| Finding | Fix |
|---|---|
| Pub/Sub rejects a publish request whose messages have different ordering keys (`FAILED_PRECONDITION: ... all messages must have no ordering key or they must all have the same ordering key`) | The publisher sends one request per ordering key, keeping each key's messages in order, and returns ids in the caller's order |
| `updateMetadata` with a stale ETag patched the *old* generation, which no longer exists in an unversioned bucket, so GCS answered 404 instead of 412 | Patch the live object with an `ifGenerationMatch` precondition, which GCS answers with 412 |
| `gcloud parametermanager` sends regional locations to the global endpoint and gets `PERMISSION_DENIED` | The test script uses the regional endpoint (`parametermanager.<region>.rep.googleapis.com`). The Java client was already correct |
| Cloud SQL for MySQL IAM users have no privileges, so a connection naming a database is refused (`Access denied for user 'cirrus-test'@'%' to database 'app'`). PostgreSQL grants `CONNECT` to everyone | Documented in the README; the test grants `SELECT` as the admin, as an application's setup would |
| Firestore collection-group queries need indexes the emulator doesn't ask for | Terraform creates them; the driver's documentation already lists them |

## IAM used for GCP

| Who | Role | Why |
|---|---|---|
| `cirrus-test` service account | `roles/storage.admin` | create and destroy the bucket; read and write objects |
| | `roles/datastore.owner` | create and destroy the Firestore database and its indexes |
| | `roles/pubsub.admin` | create and delete topics and subscriptions; publish and pull |
| | `roles/secretmanager.admin` | secrets |
| | `roles/parametermanager.admin` | parameters, global and regional |
| | `roles/serviceusage.serviceUsageConsumer` | call APIs billed to the project |
| | `roles/cloudsql.admin` (tier 2) | create instances and database users. It also includes what an application needs: `cloudsql.instances.login` for IAM database login (`roles/cloudsql.instanceUser`) and `cloudsql.instances.connect` for the connector (`roles/cloudsql.client`) |
| the person running the tests | `roles/iam.serviceAccountTokenCreator` on `cirrus-test` | impersonate it. Project Owner doesn't include this |

The project also needs these APIs enabled: the IAM Service Account Credentials API
(`iamcredentials.googleapis.com`) for impersonation, and the Cloud SQL Admin API
(`sqladmin.googleapis.com`) for tier 2.

## Not yet run

| Tier | Services | Status |
|---|---|---|
| Azure | Blob, Service Bus, Cosmos DB, Key Vault, App Configuration | no real-cloud profile yet |
| GCP | Memorystore with IAM auth | not run: VPC-only, like ElastiCache |
