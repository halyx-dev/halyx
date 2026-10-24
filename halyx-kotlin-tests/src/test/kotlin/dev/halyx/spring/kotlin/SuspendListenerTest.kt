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
package dev.halyx.spring.kotlin

import dev.halyx.messaging.Publisher
import dev.halyx.messaging.ReceivedMessage
import dev.halyx.messaging.Subscription
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration
import dev.halyx.spring.messaging.HalyxListener
import dev.halyx.stream.StreamProducer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `@HalyxListener` on a `suspend fun`: the coroutine settles the message or record when it completes. */
class SuspendListenerTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration::class.java))
        .withPropertyValues(
            "halyx.providers.local.type=memory",
            "halyx.topics.order-events.name=orders",
            "halyx.subscriptions.billing.name=orders-billing",
            "halyx.subscriptions.billing.topic=orders",
            "halyx.subscriptions.billing.listener.max-backoff=PT0.1S",
            "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
            "halyx.stream-producers.ledger-out.topic=ledger",
            "halyx.stream-consumers.ledger.group=billing",
            "halyx.stream-consumers.ledger.start-position=earliest",
            "halyx.stream-consumers.ledger.listener.max-backoff=PT0.1S",
            "halyx.stream-consumers.ledger.listener.poll-wait=PT0.2S",
        )

    class Listeners {
        val handled: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val attempts = AtomicInteger()
        val ledgerAttempts = AtomicInteger()
        val done = CountDownLatch(4)

        @HalyxListener("billing")
        suspend fun onOrder(message: ReceivedMessage) {
            val body = message.bodyAsString()
            delay(10)
            // Resumed on another thread: the message is settled when the coroutine completes, wherever it ran.
            withContext(Dispatchers.Default) {
                if (body == "fails-once" && attempts.getAndIncrement() == 0) {
                    throw IllegalStateException("first attempt fails")
                }
            }
            handled.add(body)
            done.countDown()
        }

        @HalyxListener("ledger")
        suspend fun onEntry(value: String) {
            delay(10)
            if (value == "fails-once" && ledgerAttempts.getAndIncrement() == 0) {
                throw IllegalStateException("first attempt fails")
            }
            handled.add("ledger:$value")
            done.countDown()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ListenerConfig {
        @Bean
        fun listeners() = Listeners()
    }

    @Test
    fun suspendListenersAckOnReturnAndRetryOnThrow() {
        runner.withUserConfiguration(ListenerConfig::class.java).run { context ->
            context.getBean("order-events", Publisher::class.java).publish("ok")
            context.getBean("order-events", Publisher::class.java).publish("fails-once")
            context.getBean("ledger-out", StreamProducer::class.java).send("k", "entry")
            context.getBean("ledger-out", StreamProducer::class.java).send("k", "fails-once")
            val listeners = context.getBean(Listeners::class.java)

            assertThat(listeners.done.await(15, TimeUnit.SECONDS)).`as`("handled ${listeners.handled}").isTrue()
            assertThat(listeners.handled)
                .containsExactlyInAnyOrder("ok", "fails-once", "ledger:entry", "ledger:fails-once")
            assertThat(listeners.attempts.get()).`as`("the failed message was retried").isEqualTo(2)
            assertThat(listeners.ledgerAttempts.get()).`as`("the failed record was retried").isEqualTo(2)
            assertThat(context.getBean("billing", Subscription::class.java).receive(10, Duration.ofMillis(300)))
                .`as`("both acked").isEmpty()
        }
    }

    class TwoParameters {
        @HalyxListener("billing")
        suspend fun onOrder(body: String, extra: String) {
        }
    }

    @Test
    fun aSuspendListenerStillTakesOneParameter() {
        runner.withBean(TwoParameters::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("must take one parameter")
        }
    }
}
