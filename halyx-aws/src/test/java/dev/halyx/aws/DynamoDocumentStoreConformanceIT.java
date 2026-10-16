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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.tck.DocumentStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import dev.halyx.tck.DocumentRepositoryConformanceTest;
import dev.halyx.tck.ReactiveDocumentRepositoryConformanceTest;

/** The document store TCK on DynamoDB emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDocumentStoreConformanceIT extends DocumentStoreConformanceTest {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("aws", ProviderConfig.of("aws").region("us-east-1")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566)))
                        .credentials(Credentials.of("test", "test")))
                .build();
        DynamoDbClient dynamo = halyx.provider("aws").documentStore(TableConfig.of("bootstrap")).unwrap(DynamoDbClient.class);
        createTable(dynamo, "items", false);
        createTable(dynamo, "items-sorted", true);
    }

    private static void createTable(DynamoDbClient dynamo, String name, boolean sorted) {
        List<AttributeDefinition> attributes = new ArrayList<>(List.of(
                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build()));
        List<KeySchemaElement> keys = new ArrayList<>(List.of(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()));
        if (sorted) {
            attributes.add(AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build());
            keys.add(KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build());
        }
        attributes.add(AttributeDefinition.builder().attributeName("status").attributeType(ScalarAttributeType.S).build());
        attributes.add(AttributeDefinition.builder().attributeName("placedAt").attributeType(ScalarAttributeType.S).build());
        List<GlobalSecondaryIndex> indexes = new ArrayList<>(List.of(GlobalSecondaryIndex.builder().indexName(BY_STATUS)
                .keySchema(KeySchemaElement.builder().attributeName("status").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("placedAt").keyType(KeyType.RANGE).build())
                .projection(p -> p.projectionType(ProjectionType.ALL)).build()));
        if (!sorted) {
            attributes.add(AttributeDefinition.builder().attributeName("owner").attributeType(ScalarAttributeType.S).build());
            indexes.add(GlobalSecondaryIndex.builder().indexName(BY_OWNER)
                    .keySchema(KeySchemaElement.builder().attributeName("owner").keyType(KeyType.HASH).build())
                    .projection(p -> p.projectionType(ProjectionType.ALL)).build());
        }
        dynamo.createTable(b -> b.tableName(name).attributeDefinitions(attributes).keySchema(keys)
                .globalSecondaryIndexes(indexes).billingMode(BillingMode.PAY_PER_REQUEST));
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(name));
        dynamo.updateTimeToLive(b -> b.tableName(name).timeToLiveSpecification(t -> t.attributeName("halyx_expires").enabled(true)));
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    @Override
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return halyx.provider("aws").documentStore(indexes(table, sorted).build());
    }

    /** The repository kit on the same tables. */
    @Nested
    class Repositories extends DocumentRepositoryConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            return DynamoDocumentStoreConformanceIT.this.store(sorted);
        }
    }

    /** The reactive repository kit on the same tables. */
    @Nested
    class ReactiveRepositories extends ReactiveDocumentRepositoryConformanceTest {
        private final ReactiveHalyx reactive = ReactiveHalyx.withVirtualThreads(halyx);

        @AfterEach
        void closeReactive() {
            reactive.close();
        }

        @Override
        protected DocumentStore store(boolean sorted) {
            return DynamoDocumentStoreConformanceIT.this.store(sorted);
        }

        @Override
        protected ReactiveHalyx reactive() {
            return reactive;
        }
    }
}
