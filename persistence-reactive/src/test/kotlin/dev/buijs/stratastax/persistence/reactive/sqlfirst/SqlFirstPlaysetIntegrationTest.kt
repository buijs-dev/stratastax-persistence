/**
 * Copyright (c) 2021 - 2026 Buijs Software
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.buijs.stratastax.persistence.reactive.sqlfirst

import dev.buijs.stratastax.entity.core.api.asc
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.reactive.spi.pageQuery
import dev.buijs.stratastax.persistence.reactive.spi.readPage
import dev.buijs.stratastax.persistence.reactive.testfixtures.CategoryModel
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreateLoadout
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreateNote
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreatePlayset
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreateReaction
import dev.buijs.stratastax.persistence.reactive.testfixtures.Database
import dev.buijs.stratastax.persistence.reactive.testfixtures.PlaysetModel
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.eq
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.search
import dev.buijs.stratastax.persistence.reactive.testfixtures.Seed
import dev.buijs.stratastax.persistence.test.api.PersistenceTestHarness
import dev.buijs.stratastax.persistence.test.api.QueryExecutionRecorder
import dev.buijs.stratastax.persistence.test.api.assertQueryCount
import dev.buijs.stratastax.persistence.test.api.assertSingleTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * What the SQL-first playset does beyond behaving like the jOOQ-built one (see
 * [SqlFirstParityTest]): every statement it runs is literally one of [PlaysetSql]'s, a batch costs
 * no more statements than a single write, and the reads a search adds - scopes, projections, row
 * locks, a self-reference - work over the generated fragments.
 */
internal class SqlFirstPlaysetIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = Database.start()

        /** Every literal statement of [PlaysetSql]. */
        private val STATEMENTS: Set<String> =
            PlaysetSql::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map { it.get(null) as String }
                .toSet()
    }

    private val recorder = QueryExecutionRecorder()
    private val harness = PersistenceTestHarness(recorder)
    private val store = PlaysetSqlStore(listeners = listOf(recorder))

    private lateinit var owner: UUID

    @BeforeEach
    fun clean() {
        Database.truncate()
        owner = Seed.user("Ada")
    }

    private fun full(name: String) =
        CreatePlayset(
            name = name,
            ownerId = owner,
            gameId = Seed.game("Catan $name"),
            loadout = CreateLoadout("standard"),
            expansionIds = setOf(Seed.expansion("Seafarers $name")),
            notes =
                listOf(
                    CreateNote("first", reactions = listOf(CreateReaction("a"))),
                    CreateNote("second", loadout = CreateLoadout("note")),
                ),
        )

    @Test
    fun `every statement a write runs is a literal statement of PlaysetSql`() =
        runBlocking<Unit> {
            // given
            val command = full("Friday")

            // when
            val execution = harness.execute { store.create.create(command) }

            // then
            execution.assertSingleTransaction()
            val sql = execution.dataQueries.map { it.execution.query.sql }

            assertThat(sql).isNotEmpty().allMatch { it in STATEMENTS }

            assertThat(sql).containsExactly(
                PlaysetSql.INSERT_LOADOUT,
                PlaysetSql.INSERT,
                PlaysetSql.EXISTING_EXPANSIONS,
                PlaysetSql.INSERT_EXPANSIONS,
                PlaysetSql.INSERT_LOADOUT,
                PlaysetSql.INSERT_NOTE,
                PlaysetSql.INSERT_REACTION,
                PlaysetSql.SELECT_BY_IDS,
            )
        }

    @Test
    fun `createAll writes many commands with as many statements as one create`() =
        runBlocking<Unit> {
            // given
            val one = full("one")
            val many = listOf("a", "b", "c", "d", "e").map(::full)

            // when
            val single = harness.execute { store.create.create(one) }

            val batch = harness.execute { store.create.createAll(many) }

            // then
            batch.assertQueryCount(single.dataQueries.size).assertSingleTransaction()
            assertThat(
                checkNotNull(batch.result).successes.map { it.name },
            ).containsExactly("a", "b", "c", "d", "e")
            assertThat(batch.dataQueries.map { it.execution.batchSize }).contains(5, 10)
        }

    @Test
    fun `a scope restricts findAll and count on top of the search's filter`() =
        runBlocking<Unit> {
            // given
            val grace = Seed.user("Grace")
            store.create.createAll(
                listOf(
                    CreatePlayset(name = "a", ownerId = owner),
                    CreatePlayset(name = "b", ownerId = grace),
                    CreatePlayset(name = "c", ownerId = grace),
                ),
            )
            val graceOnly = eq(PlaysetModel.ownerNameField, "Grace")

            // when
            val page = store.read.findAll(search(), graceOnly)
            val count = store.read.count(search(eq(PlaysetModel.nameField, "c")), graceOnly)

            // then
            assertThat(page.items.map { it.name }).containsExactly("b", "c")
            assertThat(count).isEqualTo(1L)
        }

    @Test
    fun `a projection selects only its own columns, as a page or as a complete list`() =
        runBlocking<Unit> {
            // given
            store.create.createAll(
                listOf("a", "b", "c").map { CreatePlayset(name = it, ownerId = owner) },
            )

            // when
            val page = harness.execute { store.read.findAllNames(search(limit = 2)) }

            val all = store.read.findAllNamesList(search(limit = 1))

            // then
            assertThat(checkNotNull(page.result).items).containsExactly("a", "b")
            assertThat(checkNotNull(page.result).hasMore).isTrue()
            assertThat(
                page.dataQueries
                    .single()
                    .execution.query.sql,
            ).startsWith(PlaysetSql.SELECT_NAMES)
                .doesNotContain("jsonb_agg")
            assertThat(all).containsExactly("a", "b", "c")
        }

    @Test
    fun `findAllForUpdate outside a transaction is rejected`() {
        // given
        val query = search()

        // when
        val thrown = catchThrowable { runBlocking { store.read.findAllForUpdate(query) } }

        // then
        assertThat(
            thrown,
        ).isInstanceOf(
            IllegalStateException::class.java,
        ).hasMessageContaining("inside a transaction")
    }

    @Test
    fun `concurrent workers claim different rows with skip locked`() =
        runBlocking<Unit> {
            // given
            store.create.createAll(
                listOf("a", "b", "c", "d").map { CreatePlayset(name = it, ownerId = owner) },
            )
            val firstClaimed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            // when
            val (first, second) =
                coroutineScope {
                    val worker1 =
                        async {
                            store.transactional.transaction {
                                store.read.findAllForUpdate(search(limit = 2)).also {
                                    firstClaimed.complete(Unit)
                                    release.await()
                                }
                            }
                        }

                    firstClaimed.await()
                    val claimed2 =
                        async {
                            store.transactional.transaction {
                                store.read.findAllForUpdate(
                                    search(limit = 2),
                                )
                            }
                        }.await()
                    release.complete(Unit)
                    worker1.await() to claimed2
                }

            // then
            assertThat(first.map { it.name }).containsExactly("a", "b")
            assertThat(second.map { it.name }).containsExactly("c", "d")
        }

    @Test
    fun `a self-referencing relation selects and filters on the related row, not on the row itself`() =
        runBlocking<Unit> {
            // given
            Database.jdbc(
                "INSERT INTO category (id, name, parent_id) VALUES (1, 'games', NULL), (2, 'board', 1), (3, 'card', 1), (4, 'euro', 2)",
            )
            val queries = store.transactional.ambientQueryExecutor()
            val mapper =
                RowMapper { row ->
                    row.string("name") to
                        (
                            row.stringOrNull("parentName") to
                                row.objectList("children").map { it.string("childrenName") }
                        )
                }

            val byName = listOf(CategoryModel.nameField.asc())

            // when
            val all =
                search(sort = byName).let {
                    queries.readPage(CategorySql.search.pageQuery(it), it, mapper)
                }

            val underGames =
                search(eq(CategoryModel.parentNameField, "games"), sort = byName)
                    .let { queries.readPage(CategorySql.search.pageQuery(it), it, mapper) }

            // then
            val byCategory = all.items.toMap()
            assertThat(byCategory["board"]).isEqualTo("games" to listOf("euro"))
            assertThat(byCategory["games"]).isEqualTo(null to listOf("board", "card"))
            assertThat(underGames.items.map { it.first }).containsExactly("board", "card")
        }
}
