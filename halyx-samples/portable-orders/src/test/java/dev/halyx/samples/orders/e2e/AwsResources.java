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
package dev.halyx.samples.orders.e2e;

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.Map;

/** The sample's AWS resources on Floci, created as its infrastructure would create them, with the default names. */
final class AwsResources {

    static final String MAX_LINES = "7";
    static final String CURRENCY = "EUR";

    private AwsResources() {
    }

    /** The bucket, table, topic and queues, the event bus and its rule, and the settings and signing key. */
    static void provision(URI endpoint, String signingKey) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (S3Client s3 = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).forcePathStyle(true).build();
             DynamoDbClient dynamo = DynamoDbClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).build();
             SnsClient sns = SnsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).build();
             SqsClient sqs = SqsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).build();
             EventBridgeClient events = EventBridgeClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).build()) {
            s3.createBucket(b -> b.bucket("portable-orders-receipts"));
            dynamo.createTable(b -> b.tableName("portable-orders").billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

            String topicArn = sns.createTopic(b -> b.name("portable-orders-placed")).topicArn();
            sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(queueArn(sqs, "portable-orders-fulfilment"))
                    .attributes(Map.of("RawMessageDelivery", "true")));

            String notifications = queueArn(sqs, "portable-orders-notifications");
            events.createEventBus(b -> b.name("portable-orders"));
            events.putRule(b -> b.name("order-placed").eventBusName("portable-orders")
                    .eventPattern("{\"source\":[\"/portable-orders\"]}"));
            events.putTargets(b -> b.rule("order-placed").eventBusName("portable-orders")
                    .targets(t -> t.id("notifications").arn(notifications)));
        }
        try (Halyx halyx = Halyx.builder().register("aws", ProviderConfig.of("aws").region("us-east-1")
                .endpoint(endpoint).credentials(Credentials.of("test", "test"))).build()) {
            var settings = halyx.provider("aws").parameterStore(
                    ParameterStoreConfig.parameters().prefix("portable-orders").build());
            settings.put("orders/max-lines", MAX_LINES);
            settings.put("orders/currency", CURRENCY);
            halyx.provider("aws").parameterStore(ParameterStoreConfig.secrets().prefix("portable-orders").build())
                    .put("receipt-signing-key", signingKey);
        }
    }

    private static String queueArn(SqsClient sqs, String name) {
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        return sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }
}
