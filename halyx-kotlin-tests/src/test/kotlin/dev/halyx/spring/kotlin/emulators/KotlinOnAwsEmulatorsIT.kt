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
package dev.halyx.spring.kotlin.emulators

import dev.halyx.messaging.Publisher
import dev.halyx.nosql.Key
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration
import dev.halyx.spring.data.document.DocumentTable
import dev.halyx.spring.data.document.EnableHalyxReactiveRepositories
import dev.halyx.spring.data.document.PartitionKey
import dev.halyx.spring.data.document.SortKey
import dev.halyx.spring.messaging.HalyxListener
import dev.halyx.stream.StreamProducer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.Repository
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@JvmRecord
@DocumentTable("journal")
data class Entry(@PartitionKey val account: String, @SortKey val at: String, val amount: Long)

interface JournalEntries : Repository<Entry, Key> {
    suspend fun save(entry: Entry): Entry

    suspend fun findById(id: Key): Entry?

    fun findByAccountOrderByAtDesc(account: String): Flow<Entry>
}

/**
 * Kotlin coroutines on real drivers: `suspend` listeners on an SQS subscription and a Kafka stream consumer, and a
 * coroutine repository on DynamoDB, with Kafka and Floci in Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class KotlinOnAwsEmulatorsIT {

    companion object {
        @Container
        @JvmField
        val KAFKA = KafkaContainer(System.getProperty("kafka.image", "apache/kafka:3.9.1"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")

        @Container
        @JvmField
        val FLOCI: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
                .withExposedPorts(4566)
                .waitingFor(Wait.forListeningPort())

        lateinit var topicArn: String
        lateinit var queueUrl: String

        private fun endpoint() = URI.create("http://${FLOCI.host}:${FLOCI.getMappedPort(4566)}")

        @BeforeAll
        @JvmStatic
        fun createResources() {
            Admin.create(mapOf<String, Any>("bootstrap.servers" to KAFKA.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(NewTopic("ledger", 2, 1.toShort()))).all().get()
            }
            val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            DynamoDbClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint())
                .credentialsProvider(credentials).build().use { dynamo ->
                    dynamo.createTable { b ->
                        b.tableName("journal").billingMode(BillingMode.PAY_PER_REQUEST)
                            .attributeDefinitions(
                                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                            .keySchema(
                                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                    }
                    dynamo.waiter().waitUntilTableExists { it.tableName("journal") }
                }
            SnsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint()).credentialsProvider(credentials)
                .build().use { sns ->
                    SqsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint())
                        .credentialsProvider(credentials).build().use { sqs ->
                            topicArn = sns.createTopic { it.name("orders") }.topicArn()
                            queueUrl = sqs.createQueue { b ->
                                b.queueName("orders-billing").attributes(mapOf(QueueAttributeName.VISIBILITY_TIMEOUT to "2"))
                            }.queueUrl()
                            val queueArn = sqs.getQueueAttributes { it.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN) }
                                .attributes()[QueueAttributeName.QUEUE_ARN]
                            sns.subscribe { b ->
                                b.topicArn(topicArn).protocol("sqs").endpoint(queueArn)
                                    .attributes(mapOf("RawMessageDelivery" to "true"))
                            }
                        }
                }
        }
    }

    class Listeners {
        val handled: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val orderAttempts = AtomicInteger()
        val ledgerAttempts = AtomicInteger()

        @HalyxListener("billing")
        suspend fun onOrder(body: String) {
            delay(10)
            if (body == "fails-once" && orderAttempts.getAndIncrement() == 0) {
                throw IllegalStateException("first attempt fails")
            }
            handled.add("order:$body")
        }

        @HalyxListener("ledger")
        suspend fun onEntry(value: String) {
            delay(10)
            if (value == "fails-once" && ledgerAttempts.getAndIncrement() == 0) {
                throw IllegalStateException("first attempt fails")
            }
            handled.add("ledger:$value")
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableHalyxReactiveRepositories(basePackageClasses = [JournalEntries::class])
    class App {
        @Bean
        fun listeners() = Listeners()
    }

    private fun runner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration::class.java, HalyxReactorAutoConfiguration::class.java))
        .withUserConfiguration(App::class.java)
        .withPropertyValues(
            "halyx.providers.aws.type=aws",
            "halyx.providers.aws.region=us-east-1",
            "halyx.providers.aws.endpoint=${endpoint()}",
            "halyx.providers.aws.credentials.type=static",
            "halyx.providers.aws.credentials.id=test",
            "halyx.providers.aws.credentials.secret=test",
            "halyx.document-stores.journal.table=journal",
            "halyx.document-stores.journal.sort-key=sk",
            "halyx.topics.order-events.name=$topicArn",
            "halyx.subscriptions.billing.name=$queueUrl",
            "halyx.subscriptions.billing.listener.max-backoff=PT0.5S",
            "halyx.stream-clusters.events.bootstrap-servers=${KAFKA.bootstrapServers}",
            "halyx.stream-producers.ledger-out.topic=ledger",
            "halyx.stream-consumers.ledger.start-position=earliest",
            "halyx.stream-consumers.ledger.listener.poll-wait=PT0.5S",
            "halyx.stream-consumers.ledger.listener.max-backoff=PT0.5S",
        )

    @Test
    fun suspendListenersAckCommitAndRetry() {
        runner().run { context ->
            context.getBean("order-events", Publisher::class.java).publish("ok")
            context.getBean("order-events", Publisher::class.java).publish("fails-once")
            context.getBean("ledger-out", StreamProducer::class.java).send("a", "entry")
            context.getBean("ledger-out", StreamProducer::class.java).send("b", "fails-once")

            val listeners = context.getBean(Listeners::class.java)
            val expected = setOf("order:ok", "order:fails-once", "ledger:entry", "ledger:fails-once")
            val deadline = Instant.now().plusSeconds(60)
            while (!listeners.handled.containsAll(expected)) {
                assertThat(Instant.now()).`as`("handled ${listeners.handled}").isBefore(deadline)
                Thread.sleep(100)
            }
            assertThat(listeners.orderAttempts.get()).`as`("the failed message was redelivered").isEqualTo(2)
            assertThat(listeners.ledgerAttempts.get()).`as`("the failed record was retried").isEqualTo(2)
        }
    }

    @Test
    fun coroutineRepositoryOnDynamoDb() {
        runner().run { context ->
            val journal = context.getBean(JournalEntries::class.java)
            runBlocking {
                journal.save(Entry("acc-1", "2026-10-24T09:00", 100))
                journal.save(Entry("acc-1", "2026-10-24T10:00", -40))

                assertThat(journal.findById(Key.of("acc-1", "2026-10-24T09:00"))?.amount).isEqualTo(100)
                assertThat(journal.findById(Key.of("acc-1", "missing"))).isNull()
                assertThat(journal.findByAccountOrderByAtDesc("acc-1").map { it.amount }.toList()).containsExactly(-40, 100)
            }
        }
    }
}
