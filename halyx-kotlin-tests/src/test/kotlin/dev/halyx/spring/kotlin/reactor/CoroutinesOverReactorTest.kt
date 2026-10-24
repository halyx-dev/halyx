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
package dev.halyx.spring.kotlin.reactor

import dev.halyx.Halyx
import dev.halyx.config.ProviderConfig
import dev.halyx.reactor.ReactiveHalyx
import dev.halyx.storage.BlobContent
import dev.halyx.storage.BlobKey
import io.micrometer.context.ContextRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.util.context.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

/** `kotlinx-coroutines-reactor` over the Reactor API: `awaitSingle`, `awaitSingleOrNull` and `asFlow`. */
class CoroutinesOverReactorTest {

    companion object {
        private val TENANT = ThreadLocal<String>()

        init {
            ContextRegistry.getInstance().registerThreadLocalAccessor("tenant", TENANT::get, TENANT::set, TENANT::remove)
        }
    }

    private val halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build()
    private val rx = ReactiveHalyx.withVirtualThreads(halyx)
    private val invoices = rx.provider("mem").blobStore("acme-invoices")

    @AfterEach
    fun close() {
        rx.close()
        halyx.close()
    }

    @Test
    fun awaitAMonoAndCollectAFlux() = runBlocking<Unit> {
        invoices.put(BlobKey.of("2026/inv-1.pdf"), BlobContent.ofString("one")).awaitSingle()
        invoices.put(BlobKey.of("2026/inv-2.pdf"), BlobContent.ofString("two")).awaitSingle()

        assertThat(invoices.getBytes(BlobKey.of("2026/inv-1.pdf")).awaitSingle().decodeToString()).isEqualTo("one")
        assertThat(invoices.head(BlobKey.of("2026/missing.pdf")).awaitSingleOrNull()).isNull()
        assertThat(invoices.listAll("2026/").asFlow().map { it.key().value() }.toList())
            .containsExactly("2026/inv-1.pdf", "2026/inv-2.pdf")
    }

    @Test
    fun aFlowPullsOnlyWhatItTakesAndClosesTheStream() = runBlocking<Unit> {
        val pulled = AtomicInteger()
        val closed = AtomicBoolean()
        val rows = rx.stream { Stream.iterate(1) { it + 1 }.peek { pulled.incrementAndGet() }.onClose { closed.set(true) } }

        assertThat(rows.asFlow().take(3).toList()).containsExactly(1, 2, 3)
        assertThat(closed.get()).`as`("closed when the flow stopped").isTrue()
        assertThat(pulled.get()).`as`("pulled as asked, not all at once").isLessThan(1_000)
    }

    @Test
    fun cancellingTheCoroutineDropsTheResultAndTheCallFinishes() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = CompletableFuture<String>()
        val call = rx.call {
            started.countDown()
            outcome.complete(finishOrInterrupt(release))
            "done"
        }

        val waiting = async(Dispatchers.Default) { call.awaitSingle() }
        assertThat(started.await(5, TimeUnit.SECONDS)).`as`("the call started").isTrue()
        waiting.cancelAndJoin()
        release.countDown()
        assertThat(waiting.isCancelled).isTrue()
        assertThat(outcome.get(5, TimeUnit.SECONDS)).isEqualTo("finished")
    }

    @Test
    fun aWithTimeoutThrowsAndTheCallFinishes() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val outcome = CompletableFuture<String>()

        assertThatThrownBy {
            runBlocking { withTimeout(50) { rx.call { outcome.complete(finishOrInterrupt(release)) }.awaitSingle() } }
        }.isInstanceOf(TimeoutCancellationException::class.java)
        release.countDown()
        assertThat(outcome.get(5, TimeUnit.SECONDS)).isEqualTo("finished")
    }

    private fun finishOrInterrupt(release: CountDownLatch): String = try {
        release.await()
        "finished"
    } catch (e: InterruptedException) {
        "interrupted"
    }

    @Test
    fun callsSeeTheCoroutinesReactorContext() = runBlocking<Unit> {
        val tenant = withContext(ReactorContext(Context.of("tenant", "acme"))) {
            rx.call { TENANT.get() }.awaitSingle()
        }
        assertThat(tenant).isEqualTo("acme")
    }

    @Test
    fun callsSeeTheSubscribingThreadsThreadLocals() = runBlocking<Unit> {
        TENANT.set("globex")
        try {
            assertThat(rx.call { TENANT.get() }.awaitSingle()).isEqualTo("globex")
        } finally {
            TENANT.remove()
        }
    }
}
