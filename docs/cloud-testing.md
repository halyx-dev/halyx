# Cloud testing

Every Cirrus driver passes its conformance kit (TCK) against emulators on each build. This file logs runs
of the same kits against **real cloud services**. Those runs catch what emulators don't model, such as IAM,
eventual consistency and asynchronous deletes.

## How a run works

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

## Tier 1, AWS: latest result

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

## Run history

| Run id | Scope | Result | Teardown | Notes |
|---|---|---|---|---|
| `r26101113239811` | full | 148 passed, 22 errors, 2 skipped | clean | Secrets Manager 0/22: the IAM user had no Secrets Manager permissions. This also exposed that `AwsErrors` mapped a 400 `AccessDeniedException` to an invalid argument instead of access denied. |
| `r26101113365837` | Secrets Manager | 14/22 | clean | `BatchGetSecretValue` not yet granted. Writes failed while a deleted name was still being deleted, and listings lagged behind writes. |
| `r2610111345e031` | Secrets Manager | 19/22 | clean | A read during a pending delete threw. `BatchGetSecretValue` was still denied right after it was granted, probably because IAM changes take time to propagate. |
| `r26101113510091` | Secrets Manager | 22/22 | clean | |
| `r26101113562b9e` | Secrets Manager | 22/22 | clean | Confirmed the batch read path with the final policy. |
| `r2610111402665b` | full | 169 passed, 1 failed, 2 skipped | clean | Secrets Manager `nativeNamesAreUsedAsTheyAre`: `get` returned a secret just after its delete was accepted. |
| `r2610111411c824` | full | 169 passed, 1 failed, 2 skipped | clean | Secrets Manager `watchedChangesInvalidateTheCache`: the watch saw the new value, but the read after it returned the old one. |
| `r2610111422284e` | full | **170 passed, 2 skipped** | clean | Latest result, above. |

## What real AWS found

Emulators missed all of these:

| Finding | Fix |
|---|---|
| A 400 `AccessDeniedException` (and other auth error codes) was mapped by status to `InvalidArgument` | `AwsErrors` checks auth error codes before status codes, so they map to `AccessDenied` |
| A deleted secret's name can't be reused for a few seconds ("marked for deletion") | `put` waits for the delete to finish (up to 30s); `delete` treats an in-progress delete as done |
| Reading a secret whose delete is still in progress throws | `get` returns empty |
| `GetSecretValue` can return a secret for a moment after its delete is accepted | `delete` waits until reads no longer see the secret (up to 10s) |
| `ListSecrets` lags behind writes | Documented as eventual. The Secrets Manager TCK allows a listing lag (`listingLag()`) |
| A listing can see a new value before `GetSecretValue` does | When a watch reports a change, it caches the values it saw, so reads agree with the event on every provider |
| Not every policy grants `BatchGetSecretValue` | If batch reads are denied, `getAll` reads secrets one at a time and logs a warning |

## IAM used for tier 1

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

## Not yet run

| Tier | Services | Status |
|---|---|---|
| AWS tier 2 | RDS with IAM auth (`cirrus-sql`) | not run |
| AWS tier 3 | ElastiCache with IAM auth | not run |
| Azure | Blob, Service Bus, Cosmos DB, Key Vault, App Configuration | no real-cloud profile yet |
| GCP | GCS, Pub/Sub, Firestore, Secret Manager, Parameter Manager | no real-cloud profile yet |
