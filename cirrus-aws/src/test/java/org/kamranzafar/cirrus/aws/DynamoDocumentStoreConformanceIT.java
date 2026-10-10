package org.kamranzafar.cirrus.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.tck.DocumentStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** The document store TCK on DynamoDB emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDocumentStoreConformanceIT extends DocumentStoreConformanceTest {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder()
                .register("aws", ProviderConfig.of("aws").region("us-east-1")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566)))
                        .credentials(Credentials.of("test", "test")))
                .build();
        DynamoDbClient dynamo = cirrus.provider("aws").documentStore(TableConfig.of("bootstrap")).unwrap(DynamoDbClient.class);
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
        dynamo.createTable(b -> b.tableName(name).attributeDefinitions(attributes).keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST));
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(name));
        dynamo.updateTimeToLive(b -> b.tableName(name).timeToLiveSpecification(t -> t.attributeName("cirrus_expires").enabled(true)));
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    @Override
    protected DocumentStore store(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return cirrus.provider("aws").documentStore(table.build());
    }
}
