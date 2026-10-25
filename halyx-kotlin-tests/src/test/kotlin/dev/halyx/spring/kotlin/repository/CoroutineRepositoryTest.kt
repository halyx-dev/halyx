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
package dev.halyx.spring.kotlin.repository

import dev.halyx.Halyx
import dev.halyx.config.ProviderConfig
import dev.halyx.nosql.DocumentStore
import dev.halyx.nosql.Key
import dev.halyx.nosql.TableConfig
import dev.halyx.reactor.ReactiveHalyx
import dev.halyx.spring.data.document.DocumentRepository
import dev.halyx.spring.data.document.DocumentTable
import dev.halyx.spring.data.document.EnableHalyxReactiveRepositories
import dev.halyx.spring.data.document.EnableHalyxRepositories
import dev.halyx.spring.data.document.PartitionKey
import dev.halyx.spring.data.document.ReactiveDocumentRepository
import dev.halyx.spring.data.document.SortKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Limit
import org.springframework.data.domain.ScrollPosition
import org.springframework.data.domain.Window
import org.springframework.data.repository.Repository

/** Halyx stores records, so a Kotlin entity is a `@JvmRecord data class`. */
@JvmRecord
@DocumentTable("events")
data class Event(@PartitionKey val stream: String, @SortKey val at: String, val kind: String)

/** A coroutine repository: `suspend` CRUD methods and derived queries, and `Flow` for many results. */
interface EventLog : Repository<Event, Key> {
    suspend fun save(event: Event): Event

    fun saveAll(events: Flow<Event>): Flow<Event>

    suspend fun findById(id: Key): Event?

    suspend fun existsById(id: Key): Boolean

    suspend fun deleteById(id: Key)

    fun findByStreamOrderByAtDesc(stream: String): Flow<Event>

    suspend fun findFirstByStreamOrderByAtDesc(stream: String): Event?

    suspend fun existsByStream(stream: String): Boolean

    suspend fun findByStream(stream: String, position: ScrollPosition, limit: Limit): Window<Event>
}

/** The reactive repository's own methods, with Kotlin query methods next to them. */
interface ReactiveEvents : ReactiveDocumentRepository<Event, Key> {
    fun findByStreamOrderByAtDesc(stream: String): Flow<Event>
}

/** A blocking repository in the same package: each annotation takes only its own kind. */
interface BlockingEvents : DocumentRepository<Event, Key> {
    fun findByStreamOrderByAtDesc(stream: String): List<Event>
}

@Configuration(proxyBeanMethods = false)
class Stores {
    @Bean(destroyMethod = "close")
    fun halyx(): Halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build()

    @Bean
    fun events(halyx: Halyx): DocumentStore =
        halyx.provider("mem").documentStore(TableConfig.builder("events").sortKey("sk").build())

    @Bean(destroyMethod = "close")
    fun reactiveHalyx(halyx: Halyx): ReactiveHalyx = ReactiveHalyx.withVirtualThreads(halyx)
}

@Configuration(proxyBeanMethods = false)
@EnableHalyxRepositories
@EnableHalyxReactiveRepositories
class Repositories

class CoroutineRepositoryTest {

    private val context = AnnotationConfigApplicationContext(Stores::class.java, Repositories::class.java)
    private val log = context.getBean(EventLog::class.java)

    @AfterEach
    fun close() = context.close()

    private suspend fun open(vararg ats: String) =
        log.saveAll(flowOf(*ats.map { Event("s1", it, "at-$it") }.toTypedArray())).toList()

    @Test
    fun suspendCrudMethods() = runBlocking<Unit> {
        assertThat(log.save(Event("s1", "10:00", "open")).kind).isEqualTo("open")

        assertThat(log.findById(Key.of("s1", "10:00"))?.kind).isEqualTo("open")
        assertThat(log.findById(Key.of("s1", "nope"))).isNull()
        assertThat(log.existsById(Key.of("s1", "10:00"))).isTrue()

        log.deleteById(Key.of("s1", "10:00"))
        assertThat(log.existsById(Key.of("s1", "10:00"))).isFalse()
    }

    @Test
    fun flowQueriesReturnEveryMatch() = runBlocking<Unit> {
        assertThat(open("10:00", "11:00", "12:00")).hasSize(3)

        assertThat(log.findByStreamOrderByAtDesc("s1").map { it.at }.toList())
            .containsExactly("12:00", "11:00", "10:00")
        assertThat(log.findByStreamOrderByAtDesc("nobody").toList()).isEmpty()
    }

    @Test
    fun suspendQueriesReturnOneResult() = runBlocking<Unit> {
        open("10:00", "11:00")

        assertThat(log.findFirstByStreamOrderByAtDesc("s1")?.at).isEqualTo("11:00")
        assertThat(log.findFirstByStreamOrderByAtDesc("nobody")).isNull()
        assertThat(log.existsByStream("s1")).isTrue()
        assertThat(log.existsByStream("nobody")).isFalse()
    }

    @Test
    fun suspendQueriesScrollWindows() = runBlocking<Unit> {
        open("10:00", "11:00", "12:00", "13:00", "14:00")

        val seen = mutableListOf<String>()
        var window = log.findByStream("s1", ScrollPosition.keyset(), Limit.of(2))
        seen += window.map { it.at }
        while (window.hasNext()) {
            window = log.findByStream("s1", window.positionAt(window.size() - 1), Limit.of(2))
            seen += window.map { it.at }
        }
        assertThat(seen).containsExactly("10:00", "11:00", "12:00", "13:00", "14:00")
    }

    @Test
    fun reactiveRepositoriesTakeFlowQueries() = runBlocking<Unit> {
        open("10:00", "11:00")
        val reactive = context.getBean(ReactiveEvents::class.java)

        assertThat(reactive.findById(Key.of("s1", "10:00")).awaitSingle().kind).isEqualTo("at-10:00")
        assertThat(reactive.findByStreamOrderByAtDesc("s1").map { it.at }.toList()).containsExactly("11:00", "10:00")
    }

    @Test
    fun coroutineRepositoriesAreReactiveOnes() {
        assertThat(context.getBeansOfType(Repository::class.java).keys)
            .containsExactlyInAnyOrder("eventLog", "reactiveEvents", "blockingEvents")
        assertThat(context.getBean(BlockingEvents::class.java).findByStreamOrderByAtDesc("s1")).isEmpty()
    }
}
