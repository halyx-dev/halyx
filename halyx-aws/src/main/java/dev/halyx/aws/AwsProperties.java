/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.aws;

/** AWS-specific keys for {@link dev.halyx.config.ProviderConfig#property(String, String)}. */
public final class AwsProperties {

    /** {@code "true"} to use path-style S3 addressing, which emulators and some S3-compatible stores need. */
    public static final String S3_PATH_STYLE = "aws.s3.path-style";

    /**
     * Uploads of known length above this many bytes use S3 multipart upload. Default 64 MiB; a single
     * S3 PUT is limited to 5 GiB.
     */
    public static final String S3_MULTIPART_THRESHOLD = "aws.s3.multipart-threshold";

    public static final long DEFAULT_MULTIPART_THRESHOLD = 64L * 1024 * 1024;

    /**
     * SDK retry mode for S3, SSM and Secrets Manager: {@code adaptive} (default), {@code standard} or
     * {@code legacy}. Adaptive adds a client-side rate limiter that slows requests down while the service
     * is throttling (S3 503 SlowDown), instead of retrying at full speed. Its rate limiter is shared by
     * everything the client calls, so a throttled bucket also slows calls to other buckets on the same
     * connection; use {@code standard} if that matters more than throttling recovery.
     */
    public static final String RETRY_MODE = "aws.retry-mode";

    /** Topic property: {@code sqs} to send straight to an SQS queue instead of an SNS topic ({@code sns}, the default). */
    public static final String MESSAGING_TARGET = "aws.target";

    /** SQL property: the region RDS IAM tokens are signed for, when it differs from the connection's region. */
    public static final String RDS_REGION = "aws.rds.region";

    /** Stream cluster property: the region MSK IAM tokens are signed for, when it differs from the connection's region. */
    public static final String MSK_REGION = "aws.msk.region";

    /** Cache property: replication group id or serverless cache name, signed into ElastiCache IAM tokens. */
    public static final String ELASTICACHE_CACHE_NAME = "aws.elasticache.cache-name";

    /** Cache property: {@code "true"} for ElastiCache Serverless caches. */
    public static final String ELASTICACHE_SERVERLESS = "aws.elasticache.serverless";

    /** Function property: the Lambda version or alias to invoke, such as {@code live}; the unpublished version if unset. */
    public static final String LAMBDA_QUALIFIER = "aws.lambda.qualifier";

    private AwsProperties() {
    }
}
