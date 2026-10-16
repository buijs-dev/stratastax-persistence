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
package dev.buijs.stratastax.persistence.reactive

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.entity.core.api.PatchField
import dev.buijs.stratastax.entity.core.api.Sort
import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.entity.core.api.SortField
import dev.buijs.stratastax.entity.core.api.toTargetIdsOrNull
import dev.buijs.stratastax.persistence.core.api.EntityNotFoundException
import dev.buijs.stratastax.persistence.core.api.MapRowContext
import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException
import dev.buijs.stratastax.persistence.core.spi.BatchItemResult
import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.core.spi.DeleteBatchItem
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.core.spi.UpdateBatchItem
import dev.buijs.stratastax.persistence.core.spi.UpdateMapper
import dev.buijs.stratastax.persistence.reactive.api.JooqCreateAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqDeleteAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqField
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqReadAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.api.JooqUpdateAccess
import dev.buijs.stratastax.persistence.reactive.api.findAllProjectionList
import dev.buijs.stratastax.persistence.reactive.app.CreateValues
import dev.buijs.stratastax.persistence.reactive.app.GroupFkResolve
import dev.buijs.stratastax.persistence.reactive.app.InitialVersionStrategy
import dev.buijs.stratastax.persistence.reactive.app.JunctionCascadeTarget
import dev.buijs.stratastax.persistence.reactive.app.JunctionDeleteWrite
import dev.buijs.stratastax.persistence.reactive.app.JunctionTargetResolve
import dev.buijs.stratastax.persistence.reactive.app.JunctionWrite
import dev.buijs.stratastax.persistence.reactive.app.JunctionWriteMapper
import dev.buijs.stratastax.persistence.reactive.app.OneToManyCreateWrite
import dev.buijs.stratastax.persistence.reactive.app.OneToManyUpdateWrite
import dev.buijs.stratastax.persistence.reactive.app.OwnedChildCreateWrite
import dev.buijs.stratastax.persistence.reactive.app.OwnedChildDeleteWrite
import dev.buijs.stratastax.persistence.reactive.app.UpdateValues
import dev.buijs.stratastax.persistence.reactive.app.VersionIncrementStrategy
import dev.buijs.stratastax.persistence.reactive.app.toUpdateEntry
import dev.buijs.stratastax.persistence.reactive.testfixtures.APP_USER
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreateNoteTag
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreatePlayset
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreatePlaysetNote
import dev.buijs.stratastax.persistence.reactive.testfixtures.EXPANSION
import dev.buijs.stratastax.persistence.reactive.testfixtures.FakeQueryExecutor
import dev.buijs.stratastax.persistence.reactive.testfixtures.FakeTransactionalExecutor
import dev.buijs.stratastax.persistence.reactive.testfixtures.PLAYSET
import dev.buijs.stratastax.persistence.reactive.testfixtures.PLAYSET_EXPANSION
import dev.buijs.stratastax.persistence.reactive.testfixtures.PLAYSET_GAME
import dev.buijs.stratastax.persistence.reactive.testfixtures.PLAYSET_NOTE
import dev.buijs.stratastax.persistence.reactive.testfixtures.PLAYSET_NOTE_TAG
import dev.buijs.stratastax.persistence.reactive.testfixtures.PlaysetEntity
import dev.buijs.stratastax.persistence.reactive.testfixtures.UpdateNoteTag
import dev.buijs.stratastax.persistence.reactive.testfixtures.UpdatePlayset
import dev.buijs.stratastax.persistence.reactive.testfixtures.UpdatePlaysetNote
import dev.buijs.stratastax.search.core.api.SearchQuery
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.jooq.SQLDialect
import org.jooq.TableField
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exercises [JooqReadAccess]/[JooqCreateAccess]/[JooqUpdateAccess]/ [JooqDeleteAccess] against a
 * no-database [FakeQueryExecutor] - jOOQ can build and render a [org.jooq.Query] with no live
 * connection at all, so these tests cover the query-building/row-mapping logic without needing a
 * real Postgres instance (that's what `persistence-vertx`'s integration tests are for).
 *
 * `create`/`update` both re-select the written row afterward (see
 * [JooqCreateAccess]/[JooqUpdateAccess]'s own kdoc for why), so [FakeQueryExecutor.rows] - not just
 * `returningRow` - must be set up for every create/update fixture here; `executor.lastQuery` in an
 * update test is therefore the re-select `SELECT`, not the `UPDATE` itself - assertions on the
 * `UPDATE`'s own shape (bind count, `+ 1` increment, ...) read `executor.allQueries.first()`
 * instead.
 */
internal class JooqAccessTest {

    private val dsl = DSL.using(SQLDialect.POSTGRES)

    private val idFieldDef =
        EntityField(name = "id", type = EntityFieldType.UUID, sortable = true, filterable = true)

    private val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = PLAYSET.ID, selectable = true),
                    "name" to JooqField(field = PLAYSET.NAME, selectable = true),
                    "version" to JooqField(field = PLAYSET.VERSION, selectable = true),
                ),
        )

    private val idField = idFieldDef

    private val rowMapper =
        RowMapper { row ->
            PlaysetEntity(
                id = row.string("id"),
                name = row.string("name"),
                version = row.long("version"),
            )
        }

    private fun playsetRow(
        id: String,
        name: String,
        version: Long,
    ) = MapRowContext(
        mapOf(
            "id" to id,
            "name" to name,
            "version" to version,
            // The junction-write path's own parentRootColumn read-back -
            // PLAYSET.ID and idField coincide in this fixture (see the
            // `JunctionWrite(..., parentRootColumn = PLAYSET.ID, ...)`
            // construction sites below), so the same value is correct
            // here too.
            "__junctionRoot_id" to id,
        ),
    )

    private fun readAccess(executor: FakeQueryExecutor) =
        object : JooqReadAccess<String, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val fieldMapping = mapping
            override val queryExecutor = executor
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
        }

    @Test
    fun `a single class can implement all four access interfaces at once without a property clash`() {
        // createJunctionWrites/updateJunctionWrites are named distinctly for
        // exactly this reason - a single generated repository implementing
        // JooqReadAccess + JooqCreateAccess + JooqUpdateAccess +
        // JooqDeleteAccess
        // (as PersistenceGenerator's output is designed to be combined) would
        // otherwise fail to compile with a Kotlin property-type clash on a
        // shared `junctionWrites` name. fieldMapping/queryTranslator/idField
        // are shared property names across all three of
        // JooqReadAccess/JooqCreateAccess/JooqUpdateAccess with identical
        // types, so one override already satisfies all three.
        val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 1L)))
        val repository =
            object :
                JooqReadAccess<String, PlaysetEntity>,
                JooqCreateAccess<CreatePlayset, PlaysetEntity>,
                JooqUpdateAccess<String, UpdatePlayset, PlaysetEntity>,
                JooqDeleteAccess<String> {

                override val dsl = this@JooqAccessTest.dsl
                override val table = PLAYSET
                override val rowMapper = this@JooqAccessTest.rowMapper
                override val fieldMapping = mapping
                override val queryExecutor = executor
                override val queryTranslator = JooqSearchQueryTranslator()
                override val idField = this@JooqAccessTest.idField
                override val createMapper =
                    CreateMapper<CreatePlayset, CreateValues> { cmd ->
                        CreateValues(PLAYSET.NAME to cmd.name)
                    }

                override val updateMapper =
                    UpdateMapper<UpdatePlayset, UpdateValues> { cmd ->
                        UpdateValues(PLAYSET.NAME to cmd.name)
                    }

                override val idColumn = PLAYSET.ID
                override val versionColumn = PLAYSET.VERSION
                override val transactionalExecutor = FakeTransactionalExecutor(executor)
                override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

                override fun mapId(id: String): Any = id
            }

        assertThat(repository.createJunctionWrites.isEmpty()).isTrue()
        assertThat(repository.updateJunctionWrites.isEmpty()).isTrue()
    }

    @Test
    fun `findById maps the returned row`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 1L)))
            val access = readAccess(executor)

            val result = access.findById("abc")

            assertThat(result).isEqualTo(PlaysetEntity("abc", "Catan", 1L))
        }
    }

    @Test
    fun `findById returns null when nothing matches`() {
        runBlocking {
            val access = readAccess(FakeQueryExecutor(rows = emptyList()))

            assertThat(access.findById("missing")).isNull()
        }
    }

    @Test
    fun `findAll maps every row and reports hasMore correctly`() {
        runBlocking {
            val rows =
                listOf(
                    playsetRow("a", "Catan", 1L),
                    playsetRow("b", "Carcassonne", 1L),
                    playsetRow("c", "Azul", 1L),
                )
            val access = readAccess(FakeQueryExecutor(rows = rows))

            val page =
                access.findAll(
                    SearchQuery(
                        limit = 2,
                        sort = Sort(listOf(SortField(idField, SortDirection.ASC))),
                        filter = null,
                        cursor = null,
                    ),
                )

            assertThat(page.items.size).isEqualTo(2)
            assertThat(page.hasMore).isTrue()
        }
    }

    @Test
    fun `count returns the executor's count and builds a query with no ORDER BY or LIMIT`() {
        runBlocking {
            val executor = FakeQueryExecutor(countResult = 3L)
            val access = readAccess(executor)

            val result =
                access.count(
                    SearchQuery(
                        limit = 2,
                        sort = Sort(listOf(SortField(idField, SortDirection.ASC))),
                        filter = null,
                        cursor = null,
                    ),
                )

            assertThat(result).isEqualTo(3L)
            val sql = executor.lastQuery!!.sql.lowercase()
            assertThat(sql.contains("order by")).isFalse()
            assertThat(sql.contains("limit")).isFalse()
        }
    }

    @Test
    fun `findAllProjectionList returns every row and builds a query without LIMIT`() {
        runBlocking {
            val rows =
                listOf(
                    playsetRow("a", "Catan", 1L),
                    playsetRow("b", "Carcassonne", 1L),
                    playsetRow("c", "Azul", 1L),
                )
            val executor = FakeQueryExecutor(rows = rows)
            val access = readAccess(executor)

            val result =
                access.findAllProjectionList(
                    SearchQuery(
                        limit = 2,
                        sort = Sort(listOf(SortField(idField, SortDirection.ASC))),
                        filter = null,
                        cursor = null,
                    ),
                    fields = setOf("id"),
                    rowMapper = RowMapper { row -> row.string("id") },
                )

            assertThat(result).containsExactly("a", "b", "c")
            val sql = executor.lastQuery!!.sql.lowercase()
            assertThat(sql.contains("order by")).isTrue()
            assertThat(sql.contains("limit")).isFalse()
        }
    }

    private fun createAccess(
        executor: FakeQueryExecutor,
        withJunctionWrites: Boolean = false,
        withOneToManyWrites: Boolean = false,
        withOneToManyElementFkResolve: Boolean = false,
        withNestedOneToManyWrites: Boolean = false,
        targetResolve: JunctionTargetResolve? = null,
    ) = object : JooqCreateAccess<CreatePlayset, PlaysetEntity> {

        override val dsl = this@JooqAccessTest.dsl
        override val table = PLAYSET
        override val rowMapper = this@JooqAccessTest.rowMapper
        override val queryExecutor = executor
        override val createMapper =
            CreateMapper<CreatePlayset, CreateValues> { cmd ->
                CreateValues(PLAYSET.NAME to cmd.name)
            }

        override val fieldMapping = mapping
        override val queryTranslator = JooqSearchQueryTranslator()
        override val idField = this@JooqAccessTest.idField
        override val transactionalExecutor = FakeTransactionalExecutor(executor)
        override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

        override val createJunctionWrites =
            if (withJunctionWrites) {
                listOf(
                    JunctionWrite(
                        table = PLAYSET_EXPANSION,
                        rootColumn = PLAYSET_EXPANSION.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        targetColumn = PLAYSET_EXPANSION.EXPANSION_ID,
                        mapper = JunctionWriteMapper<CreatePlayset> { it.expansionIds },
                        targetResolve = targetResolve,
                    ),
                )
            } else {
                emptyList()
            }

        override val createOneToManyWrites =
            if (withOneToManyWrites) {
                listOf(
                    OneToManyCreateWrite(
                        childTable = PLAYSET_NOTE,
                        parentColumn = PLAYSET_NOTE.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        elements = { cmd: CreatePlayset -> cmd.notes },
                        elementMapper = { note ->
                            CreateValues(
                                PLAYSET_NOTE.ID to note.id,
                                PLAYSET_NOTE.TEXT to note.text,
                            )
                        },
                        elementFkResolves =
                            if (withOneToManyElementFkResolve) {
                                listOf(
                                    GroupFkResolve(
                                        fkColumn = PLAYSET_NOTE.GAME_ID,
                                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                                        targetColumn = PLAYSET_GAME.ID,
                                        valueSupplier = { it.gameId },
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        childIdentityColumn =
                            if (withNestedOneToManyWrites) {
                                PLAYSET_NOTE.ID
                            } else {
                                null
                            },
                        elementWrites =
                            if (withNestedOneToManyWrites) {
                                listOf(
                                    OneToManyCreateWrite(
                                        childTable = PLAYSET_NOTE_TAG,
                                        parentColumn = PLAYSET_NOTE_TAG.NOTE_ID,
                                        parentRootColumn = PLAYSET_NOTE.ID,
                                        elements = { note: CreatePlaysetNote -> note.tags },
                                        elementMapper = { tag ->
                                            CreateValues(
                                                PLAYSET_NOTE_TAG.ID to tag.id,
                                                PLAYSET_NOTE_TAG.LABEL to tag.label,
                                            )
                                        },
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                    ),
                )
            } else {
                emptyList()
            }
    }

    @Test
    fun `create maps the re-selected row, not the raw RETURNING row`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
            val access = createAccess(executor)

            val result = access.create(CreatePlayset(name = "Wingspan"))

            assertThat(result).isEqualTo(PlaysetEntity("new-id", "Wingspan", 0L))
            // 1 root INSERT + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(2)
            assertThat(
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
                    .startsWith("insert"),
            ).isTrue()
        }
    }

    @Test
    fun `create with junction writes inserts all target-id rows in one batch INSERT, in a transaction`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
            val access = createAccess(executor, withJunctionWrites = true)
            val expansionIds = setOf(UUID.randomUUID(), UUID.randomUUID())

            access.create(CreatePlayset(name = "Wingspan", expansionIds = expansionIds))

            // 1 root INSERT + 1 batch junction INSERT (both target ids in
            // one statement) + 1 re-select SELECT, all through the same
            // (transactional) executor.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val junctionInsert = executor.allQueries[1]
            assertThat(junctionInsert.sql.contains("playset_expansion", ignoreCase = true)).isTrue()
            expansionIds.forEach { id ->
                assertThat(junctionInsert.bindValues.contains(id)).isTrue()
            }
        }
    }

    @Test
    fun `create with junction writes but no targets issues only the root insert and the re-select`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
            val access = createAccess(executor, withJunctionWrites = true)

            access.create(CreatePlayset(name = "Wingspan", expansionIds = emptySet()))

            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `create with one-to-many writes inserts all elements in one batch INSERT, in the same transaction as the root insert`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    updateRowCounts = listOf(2),
                )
            val access = createAccess(executor, withOneToManyWrites = true)
            val note1 = CreatePlaysetNote(UUID.randomUUID(), "first note")
            val note2 = CreatePlaysetNote(UUID.randomUUID(), "second note")

            access.create(CreatePlayset(name = "Wingspan", notes = listOf(note1, note2)))

            // 1 root INSERT + 1 batch child INSERT (both notes, one
            // statement) + 1 re-select SELECT, all through the same
            // (transactional) executor.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val childInsert = executor.allQueries[1]
            assertThat(childInsert.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(note1.text)).isTrue()
            assertThat(childInsert.bindValues.contains(note2.text)).isTrue()
        }
    }

    @Test
    fun `create with one-to-many writes but no elements issues only the root insert and the re-select`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
            val access = createAccess(executor, withOneToManyWrites = true)

            access.create(CreatePlayset(name = "Wingspan", notes = emptyList()))

            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `create with a managed group nested inside another managed group's own child batches the grandchild rows using the child's own newly-generated id, in the same transaction`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    // 1 note (the note-level batch insert), then 2 tags
                    // (the tag-level batch insert, both siblings of the
                    // one note folded into a single statement).
                    updateRowCounts = listOf(1, 2),
                )
            val access =
                createAccess(executor, withOneToManyWrites = true, withNestedOneToManyWrites = true)
            val noteId = UUID.randomUUID()
            val tag1 = CreateNoteTag(UUID.randomUUID(), "urgent")
            val tag2 = CreateNoteTag(UUID.randomUUID(), "later")
            val note = CreatePlaysetNote(noteId, "first note", tags = listOf(tag1, tag2))

            access.create(CreatePlayset(name = "Wingspan", notes = listOf(note)))

            // 1 root INSERT + 1 note batch INSERT + 1 tag batch INSERT
            // (both tags in one statement) + 1 re-select SELECT, all
            // through the same (transactional) executor.
            assertThat(executor.allQueries.size).isEqualTo(4)
            val noteInsert = executor.allQueries[1]
            assertThat(noteInsert.sql.contains("playset_note", ignoreCase = true)).isTrue()
            val tagInsert = executor.allQueries[2]
            assertThat(tagInsert.sql.contains("playset_note_tag", ignoreCase = true)).isTrue()
            // The note's own new generated id, not a separately re-read
            // value - see OneToManyCreateWrite.childIdentityValue.
            assertThat(tagInsert.bindValues.contains(noteId)).isTrue()
            assertThat(tagInsert.bindValues.contains(tag1.label)).isTrue()
            assertThat(tagInsert.bindValues.contains(tag2.label)).isTrue()
        }
    }

    @Test
    fun `create with one-to-many writes resolves a nested group's identity-field FK via one batched SELECT, then a plain batch INSERT`() {
        runBlocking {
            val publicGameId = UUID.randomUUID()
            val rawGameId = 42L
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("id" to rawGameId, "public_id" to publicGameId)),
                        ),
                )
            val access =
                createAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyElementFkResolve = true,
                )
            val note = CreatePlaysetNote(UUID.randomUUID(), "first note", publicGameId)

            access.create(CreatePlayset(name = "Wingspan", notes = listOf(note)))

            // 1 root INSERT + 1 batched FK-resolve SELECT (IN-clause,
            // batched across every element that needs one) + 1 child batch
            // INSERT (the resolved raw id substituted directly, no derived
            // table) + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(4)
            val resolveSelect = executor.allQueries[1]
            assertThat(resolveSelect.sql.lowercase().startsWith("select")).isTrue()
            assertThat(resolveSelect.bindValues.contains(publicGameId)).isTrue()
            val childInsert = executor.allQueries[2]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(rawGameId)).isTrue()
        }
    }

    @Test
    fun `create with one-to-many writes throws ReferencedEntityNotFoundException when a nested group's FK lookup finds no row`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    updateRowCount = 0,
                    existsResult = { false },
                )
            val access =
                createAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyElementFkResolve = true,
                )
            val note = CreatePlaysetNote(UUID.randomUUID(), "first note", UUID.randomUUID())

            assertThatThrownBy {
                runBlocking {
                    access.create(CreatePlayset(name = "Wingspan", notes = listOf(note)))
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
        }
    }

    @Test
    fun `create with one-to-many writes binds a nested group's FK column to null directly for an element whose valueSupplier returns null, no batched SELECT, no exception`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    updateRowCounts = listOf(1),
                )
            val access =
                createAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyElementFkResolve = true,
                )
            val note = CreatePlaysetNote(UUID.randomUUID(), "first note", gameId = null)

            access.create(CreatePlayset(name = "Wingspan", notes = listOf(note)))

            // No lookup value at all across every element - the batched
            // FK-resolve SELECT is skipped entirely: 1 root INSERT + 1 child
            // batch INSERT + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val childInsert = executor.allQueries[1]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.sql.contains("game_id", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `create with a JunctionTargetResolve resolves supplied public ids to raw ids before writing`() {
        runBlocking {
            // The command supplies a *public* id (EXPANSION.PUBLIC_ID) for
            // each target - the real reported bug: the junction insert used
            // to bind that public id verbatim into EXPANSION_ID, which
            // actually stores the target table's own raw id.
            // PLAYSET_EXPANSION.EXPANSION_ID is UUID-typed in this fixture,
            // so the resolved "raw" id must be a real UUID too - jOOQ
            // enforces the bound value's Kotlin type against the target
            // column's own declared type at query-build time.
            val publicId1 = UUID.randomUUID()
            val publicId2 = UUID.randomUUID()
            val rawId1 = UUID.randomUUID()
            val rawId2 = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("id" to rawId1, "public_id" to publicId1)),
                            MapRowContext(mapOf("id" to rawId2, "public_id" to publicId2)),
                        ),
                )
            val access =
                createAccess(
                    executor,
                    withJunctionWrites = true,
                    targetResolve = JunctionTargetResolve(EXPANSION.PUBLIC_ID, EXPANSION.ID),
                )

            access.create(
                CreatePlayset(name = "Wingspan", expansionIds = setOf(publicId1, publicId2)),
            )

            // 1 root INSERT + 1 resolve SELECT + 1 batch junction INSERT
            // (both resolved ids in one statement) + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(4)
            val resolveQuery = executor.allQueries[1]
            assertThat(resolveQuery.sql.lowercase().startsWith("select")).isTrue()
            assertThat(resolveQuery.sql.contains("expansion", ignoreCase = true)).isTrue()

            val junctionInsert = executor.allQueries[2]
            assertThat(junctionInsert.bindValues.contains(rawId1)).isTrue()
            assertThat(junctionInsert.bindValues.contains(rawId2)).isTrue()
            assertThat(!junctionInsert.bindValues.contains(publicId1)).isTrue()
            assertThat(!junctionInsert.bindValues.contains(publicId2)).isTrue()
        }
    }

    @Test
    fun `create throws ReferencedEntityNotFoundException when a JunctionTargetResolve lookup finds no matching row`() {
        runBlocking {
            val publicId1 = UUID.randomUUID()
            val missingPublicId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    resolveRows =
                        listOf(
                            MapRowContext(
                                mapOf("id" to UUID.randomUUID(), "public_id" to publicId1),
                            ),
                        ),
                )
            val access =
                createAccess(
                    executor,
                    withJunctionWrites = true,
                    targetResolve = JunctionTargetResolve(EXPANSION.PUBLIC_ID, EXPANSION.ID),
                )

            assertThatThrownBy {
                runBlocking {
                    access.create(
                        CreatePlayset(
                            name = "Wingspan",
                            expansionIds = setOf(publicId1, missingPublicId),
                        ),
                    )
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
        }
    }

    @Test
    fun `create writes the mapper's own version value verbatim, with no automatic increment`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 5L)))
            val access =
                object : JooqCreateAccess<CreatePlayset, PlaysetEntity> {

                    override val dsl = this@JooqAccessTest.dsl
                    override val table = PLAYSET
                    override val rowMapper = this@JooqAccessTest.rowMapper
                    override val queryExecutor = executor
                    override val createMapper =
                        CreateMapper<CreatePlayset, CreateValues> { cmd ->
                            CreateValues(PLAYSET.NAME to cmd.name, PLAYSET.VERSION to 5L)
                        }

                    override val fieldMapping = mapping
                    override val queryTranslator = JooqSearchQueryTranslator()
                    override val idField = this@JooqAccessTest.idField
                    override val transactionalExecutor = FakeTransactionalExecutor(executor)
                    override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }
                }

            val result = access.create(CreatePlayset(name = "Wingspan"))

            assertThat(result).isEqualTo(PlaysetEntity("new-id", "Wingspan", 5L))
            val insertSql =
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
            assertThat(insertSql.contains("version")).isTrue()
            assertThat(!insertSql.contains("+ 1")).isTrue()
        }
    }

    @Test
    fun `create returns the id field's own bound column, not a same-named unrelated one`() {
        runBlocking {
            // "id" is deliberately bound to PUBLIC_ID here, not PLAYSET's own
            // raw "id" column (PLAYSET.ID) - reproducing a real bug: a bare
            // RETURNING * row is keyed by physical column names, so
            // extractRootId's idField.name-based lookup ("id") would
            // silently read PLAYSET.ID's value instead of the field's
            // actual bound column, PLAYSET.PUBLIC_ID. RETURNING must
            // therefore select and alias exactly PUBLIC_ID, not fall back to
            // whichever column happens to be physically named "id".
            val localIdField =
                EntityField(
                    name = "id",
                    type = EntityFieldType.UUID,
                    sortable = true,
                    filterable = true,
                )
            val localNameField =
                EntityField(
                    name = "name",
                    type = EntityFieldType.STRING,
                    sortable = true,
                    filterable = true,
                )
            val localMapping =
                JooqFieldMapping(
                    fields =
                        mapOf(
                            "id" to JooqField(field = PLAYSET.PUBLIC_ID, selectable = true),
                            "name" to JooqField(field = PLAYSET.NAME, selectable = true),
                        ),
                )
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
            val access =
                object : JooqCreateAccess<CreatePlayset, PlaysetEntity> {

                    override val dsl = this@JooqAccessTest.dsl
                    override val table = PLAYSET
                    override val rowMapper = this@JooqAccessTest.rowMapper
                    override val queryExecutor = executor
                    override val createMapper =
                        CreateMapper<CreatePlayset, CreateValues> { cmd ->
                            CreateValues(PLAYSET.NAME to cmd.name)
                        }

                    override val fieldMapping = localMapping
                    override val queryTranslator = JooqSearchQueryTranslator()
                    override val idField = localIdField
                    override val transactionalExecutor = FakeTransactionalExecutor(executor)
                    override val extractRootId: (RowContext) -> Any = { row ->
                        row.string(localIdField.name)
                    }
                }

            access.create(CreatePlayset(name = "Wingspan"))

            // RETURNING must select exactly PUBLIC_ID and nothing else - a
            // bare RETURNING * would also mention every other physical
            // column (e.g. "name", already present from this INSERT's own
            // SET clause), so this only distinguishes the two if it checks
            // the returning clause is narrowed, not merely that it mentions
            // public_id somewhere (a bare RETURNING * mentions it too).
            val insertSql =
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
            val returningClause = insertSql.substringAfter("returning")
            assertThat(returningClause.contains("public_id")).isTrue()
            assertThat(!returningClause.contains("\"name\"")).isTrue()
            assertThat(!returningClause.contains("\"version\"")).isTrue()
        }
    }

    @Test
    fun `create writes a junction's own parent-root column value, not the id field's differently-bound column`() {
        runBlocking {
            // "id" is bound to PUBLIC_ID (like the test above), but the
            // junction's own root(from = PLAYSET.ID, ...) relation is
            // declared against the entity's *other* column, PLAYSET.ID - a
            // real reported bug: the write path used to always reuse
            // extractRootId's value (PUBLIC_ID) for every junction bind,
            // which is the wrong column/value whenever it differs from the
            // junction's own declared root column.
            val localIdField =
                EntityField(
                    name = "id",
                    type = EntityFieldType.UUID,
                    sortable = true,
                    filterable = true,
                )
            val localNameField =
                EntityField(
                    name = "name",
                    type = EntityFieldType.STRING,
                    sortable = true,
                    filterable = true,
                )
            val localMapping =
                JooqFieldMapping(
                    fields =
                        mapOf(
                            "id" to JooqField(field = PLAYSET.PUBLIC_ID, selectable = true),
                            "name" to JooqField(field = PLAYSET.NAME, selectable = true),
                        ),
                )

            val publicId = "public-id"
            // A real UUID, not just any string - PLAYSET.ID/
            // PLAYSET_EXPANSION.PLAYSET_ID are UUID-typed columns, and jOOQ
            // silently binds an unconvertible value as null rather than
            // throwing, which would otherwise mask this exact bug.
            val internalId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(
                                mapOf(
                                    "id" to publicId,
                                    "name" to "Wingspan",
                                    "version" to 0L,
                                    "__junctionRoot_id" to internalId,
                                ),
                            ),
                        ),
                )
            val access =
                object : JooqCreateAccess<CreatePlayset, PlaysetEntity> {

                    override val dsl = this@JooqAccessTest.dsl
                    override val table = PLAYSET
                    override val rowMapper = this@JooqAccessTest.rowMapper
                    override val queryExecutor = executor
                    override val createMapper =
                        CreateMapper<CreatePlayset, CreateValues> { cmd ->
                            CreateValues(PLAYSET.NAME to cmd.name)
                        }

                    override val fieldMapping = localMapping
                    override val queryTranslator = JooqSearchQueryTranslator()
                    override val idField = localIdField
                    override val transactionalExecutor = FakeTransactionalExecutor(executor)
                    override val extractRootId: (RowContext) -> Any = { row ->
                        row.string(localIdField.name)
                    }

                    override val createJunctionWrites =
                        listOf(
                            JunctionWrite(
                                table = PLAYSET_EXPANSION,
                                rootColumn = PLAYSET_EXPANSION.PLAYSET_ID,
                                parentRootColumn = PLAYSET.ID,
                                targetColumn = PLAYSET_EXPANSION.EXPANSION_ID,
                                mapper = JunctionWriteMapper<CreatePlayset> { it.expansionIds },
                            ),
                        )
                }

            access.create(CreatePlayset(name = "Wingspan", expansionIds = setOf(UUID.randomUUID())))

            // RETURNING selects both the id field's own column and the
            // junction's own parent-root column.
            val insertSql =
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
            val returningClause = insertSql.substringAfter("returning")
            assertThat(returningClause.contains("public_id")).isTrue()

            // The junction INSERT's own bind values contain the *internal*
            // id, never the public one.
            val junctionInsert = executor.allQueries[1]
            assertThat(junctionInsert.bindValues.contains(internalId)).isTrue()
            assertThat(!junctionInsert.bindValues.contains(publicId)).isTrue()
        }
    }

    @Test
    fun `createAll batches every command into one INSERT and one bulk re-select on the happy path`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    allRowsSequence =
                        listOf(
                            // The batch INSERT ... RETURNING - only "id" is
                            // ever read off this one.
                            listOf(
                                playsetRow("id-1", "unused", 0L),
                                playsetRow("id-2", "unused", 0L),
                            ),
                            // The bulk re-select, keyed by id - order-
                            // independent from the RETURNING row order above.
                            listOf(
                                playsetRow("id-2", "Carcassonne", 0L),
                                playsetRow("id-1", "Catan", 0L),
                            ),
                        ),
                )
            val access = createAccess(executor)

            val result =
                access.createAll(
                    listOf(CreatePlayset(name = "Catan"), CreatePlayset(name = "Carcassonne")),
                )

            assertThat(result.successes.map { it.name }).isEqualTo(listOf("Catan", "Carcassonne"))
            assertThat(result.failures.isEmpty()).isTrue()
            // 1 batch INSERT ... RETURNING + 1 bulk re-select - never one
            // INSERT per command.
            assertThat(executor.allQueries.size).isEqualTo(2)
            assertThat(
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
                    .startsWith("insert"),
            ).isTrue()
        }
    }

    @Test
    fun `createAll falls back to per-item create when the optimized batch attempt fails`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    // The succeeding fallback create()'s own re-select -
                    // executeAndGetFirst always answers with this same row
                    // regardless of which command asked, a FakeQueryExecutor
                    // limitation; only the second command's own create() call
                    // is asserted on content here for that reason.
                    rows = listOf(playsetRow("id-2", "Carcassonne", 0L)),
                    // The batch attempt's own INSERT ... RETURNING comes back
                    // with fewer rows than commands - createAllOptimized's own
                    // sanity check throws, triggering a full per-item
                    // fallback.
                    allRowsSequence = listOf(emptyList()),
                    // Per-item fallback create()'s own INSERT ... RETURNING,
                    // in order: the first command's collapses to no row (with
                    // no createFkResolves configured, that's an "impossible"
                    // internal error rather than a referenced-entity one -
                    // still a real Failure either way); the second succeeds.
                    returningRows = listOf(null, playsetRow("id-2", "Carcassonne", 0L)),
                )
            val access = createAccess(executor)

            val result =
                access.createAll(
                    listOf(CreatePlayset(name = "Catan"), CreatePlayset(name = "Carcassonne")),
                )

            assertThat(result.items.size).isEqualTo(2)
            assertThat(result.items[0] is BatchItemResult.Failure).isTrue()
            val secondItem = result.items[1]
            assertThat(secondItem is BatchItemResult.Success).isTrue()
            assertThat((secondItem as BatchItemResult.Success).value.name).isEqualTo("Carcassonne")
            // 1 failed batch attempt + 2 per-item INSERT ... RETURNING (the
            // first throws immediately, the second also re-selects) = 4.
            assertThat(executor.allQueries.size).isEqualTo(4)
        }
    }

    private fun updateAccess(
        executor: FakeQueryExecutor,
        withVersionColumn: Boolean = true,
        withJunctionWrites: Boolean = false,
        withOneToManyWrites: Boolean = false,
        withOneToManySoftDelete: Boolean = false,
        withOneToManyElementFkResolve: Boolean = false,
        withOneToManyReplaceAll: Boolean = false,
        withOneToManyVersion: Boolean = false,
        withNestedOneToManyWrites: Boolean = false,
        withNestedOneToManySoftDelete: Boolean = false,
        customVersionIncrementStrategy: VersionIncrementStrategy? = null,
        targetResolve: JunctionTargetResolve? = null,
    ) = object : JooqUpdateAccess<String, UpdatePlayset, PlaysetEntity> {

        override val dsl = this@JooqAccessTest.dsl
        override val table = PLAYSET
        override val rowMapper = this@JooqAccessTest.rowMapper
        override val queryExecutor = executor
        override val updateMapper =
            UpdateMapper<UpdatePlayset, UpdateValues> { cmd ->
                UpdateValues(PLAYSET.NAME to cmd.name)
            }

        override val idColumn = PLAYSET.ID
        override val versionColumn = if (withVersionColumn) PLAYSET.VERSION else null
        override val versionIncrementStrategy: VersionIncrementStrategy =
            customVersionIncrementStrategy
                ?: VersionIncrementStrategy { column ->
                    DSL.field("{0} + 1", column.dataType, column)
                }

        override val fieldMapping = mapping
        override val queryTranslator = JooqSearchQueryTranslator()
        override val idField = this@JooqAccessTest.idField
        override val transactionalExecutor = FakeTransactionalExecutor(executor)
        override val updateJunctionWrites =
            if (withJunctionWrites) {
                listOf(
                    JunctionWrite(
                        table = PLAYSET_EXPANSION,
                        rootColumn = PLAYSET_EXPANSION.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        targetColumn = PLAYSET_EXPANSION.EXPANSION_ID,
                        mapper = JunctionWriteMapper<UpdatePlayset> { it.expansionIds },
                        targetResolve = targetResolve,
                    ),
                )
            } else {
                emptyList()
            }

        override val updateOneToManyWrites =
            if (withOneToManyWrites) {
                listOf(
                    OneToManyUpdateWrite(
                        childTable = PLAYSET_NOTE,
                        parentColumn = PLAYSET_NOTE.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        childIdentityColumn = PLAYSET_NOTE.ID,
                        softDeleteColumn =
                            if (withOneToManySoftDelete) {
                                PLAYSET_NOTE.DELETED_AT
                            } else {
                                null
                            },
                        elements = { cmd: UpdatePlayset -> cmd.notes },
                        // `replaceAll`'s own effect on matchedIndices/
                        // removedIds is independent of how elementId
                        // itself is computed (a real generator SPI would
                        // ignore the incoming element entirely under
                        // replaceAll - see OneToManyUpdateStrategy's own
                        // kdoc - but that's a codegen-level wiring
                        // concern, tested separately). Kept as `it.id`
                        // here so the replaceAll tests below can force a
                        // deterministic id collision with an existing
                        // row and still prove it's ignored.
                        elementId = { it.id },
                        elementMapper =
                            UpdateMapper<UpdatePlaysetNote, UpdateValues> { note ->
                                UpdateValues(PLAYSET_NOTE.TEXT to note.text)
                            },
                        elementFkResolves =
                            if (withOneToManyElementFkResolve) {
                                listOf(
                                    GroupFkResolve<UpdatePlaysetNote>(
                                        fkColumn = PLAYSET_NOTE.GAME_ID,
                                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                                        targetColumn = PLAYSET_GAME.ID,
                                        valueSupplier = { it.gameId },
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        elementWrites =
                            if (withNestedOneToManyWrites) {
                                listOf(
                                    OneToManyUpdateWrite(
                                        childTable = PLAYSET_NOTE_TAG,
                                        parentColumn = PLAYSET_NOTE_TAG.NOTE_ID,
                                        parentRootColumn = PLAYSET_NOTE.ID,
                                        childIdentityColumn = PLAYSET_NOTE_TAG.ID,
                                        softDeleteColumn =
                                            if (withNestedOneToManySoftDelete) {
                                                PLAYSET_NOTE_TAG.DELETED_AT
                                            } else {
                                                null
                                            },
                                        elements = { note: UpdatePlaysetNote -> note.tags },
                                        elementId = { it.id },
                                        elementMapper =
                                            UpdateMapper<UpdateNoteTag, UpdateValues> { tag ->
                                                UpdateValues(
                                                    PLAYSET_NOTE_TAG.LABEL to tag.label,
                                                )
                                            },
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        replaceAll = withOneToManyReplaceAll,
                        versionColumn =
                            if (withOneToManyVersion) PLAYSET_NOTE.VERSION else null,
                        initialVersion =
                            if (withOneToManyVersion) InitialVersionStrategy { 7L } else null,
                    ),
                )
            } else {
                emptyList()
            }
    }

    @Test
    fun `update maps the re-selected row when the version matches`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan (2nd ed)", 2L)))
            val access = updateAccess(executor)

            val result =
                access.update("abc", UpdatePlayset(name = "Catan (2nd ed)"), expectedVersion = 1L)

            assertThat(result).isEqualTo(PlaysetEntity("abc", "Catan (2nd ed)", 2L))
            // the version column is atomically incremented in the same UPDATE,
            // not just checked in the WHERE clause - see JooqUpdateAccess's
            // kdoc. The UPDATE is the first query - the re-select SELECT comes
            // after it.
            assertThat(executor.allQueries.size).isEqualTo(2)
            assertThat(
                executor.allQueries
                    .first()
                    .sql
                    .contains("+ 1"),
            ).isTrue()
        }
    }

    @Test
    fun `update honors a custom VersionIncrementStrategy instead of the default plus one`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan (2nd ed)", 11L)))
            val access =
                updateAccess(
                    executor,
                    customVersionIncrementStrategy =
                        VersionIncrementStrategy { column ->
                            DSL.field("{0} + 10", column.dataType, column)
                        },
                )

            access.update("abc", UpdatePlayset(name = "Catan (2nd ed)"), expectedVersion = 1L)

            assertThat(
                executor.allQueries
                    .first()
                    .sql
                    .contains("+ 10"),
            ).isTrue()
        }
    }

    @Test
    fun `update throws OptimisticLockException when the row exists but expectedVersion is stale`() {
        runBlocking {
            val executor = FakeQueryExecutor(existsResult = { true })
            val access = updateAccess(executor)

            assertThatThrownBy {
                runBlocking {
                    access.update(
                        "abc",
                        UpdatePlayset(name = "Catan (2nd ed)"),
                        expectedVersion = 99L,
                    )
                }
            }.isInstanceOf(OptimisticLockException::class.java)
            // the failed UPDATE + the diagnostic exists() check that ruled
            // out "not found" and confirmed it's really a stale version.
            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `update throws EntityNotFoundException when the row doesn't exist, even with a version column configured`() {
        runBlocking {
            val executor = FakeQueryExecutor()
            val access = updateAccess(executor)

            assertThatThrownBy {
                runBlocking {
                    access.update(
                        "abc",
                        UpdatePlayset(name = "Catan (2nd ed)"),
                        expectedVersion = 99L,
                    )
                }
            }.isInstanceOf(EntityNotFoundException::class.java)
            // the failed UPDATE + the diagnostic exists() check.
            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `update throws EntityNotFoundException with no extra query when there's no version column`() {
        runBlocking {
            val executor = FakeQueryExecutor()
            val access = updateAccess(executor, withVersionColumn = false)

            assertThatThrownBy {
                runBlocking {
                    access.update("abc", UpdatePlayset(name = "Catan"), expectedVersion = null)
                }
            }.isInstanceOf(EntityNotFoundException::class.java)
            // no version column -> zero rows can only mean "not found", so no
            // diagnostic exists() query is needed - just the failed UPDATE.
            assertThat(executor.allQueries.size).isEqualTo(1)
        }
    }

    @Test
    fun `update without a version column never binds one`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 0L)))
            val access = updateAccess(executor, withVersionColumn = false)

            access.update("abc", UpdatePlayset(name = "Catan"), expectedVersion = null)

            // SET name=? WHERE id=? - two bind values, never three (no version
            // condition bound when there's no version column configured). The
            // UPDATE is the first query.
            assertThat(
                executor.allQueries
                    .first()
                    .bindValues.size,
            ).isEqualTo(2)
        }
    }

    @Test
    fun `update with junction writes deletes then reinserts, in a transaction`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 2L)))
            val access = updateAccess(executor, withJunctionWrites = true)
            val expansionIds = setOf(UUID.randomUUID())

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", expansionIds = expansionIds),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 junction DELETE + 1 junction INSERT + 1
            // re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(4)
            val deleteSql = executor.allQueries[1].sql.lowercase()
            assertThat(deleteSql.startsWith("delete")).isTrue()
            assertThat(deleteSql.contains("playset_expansion")).isTrue()
            val insertSql = executor.allQueries[2].sql.lowercase()
            assertThat(insertSql.startsWith("insert")).isTrue()
            assertThat(insertSql.contains("playset_expansion")).isTrue()
        }
    }

    @Test
    fun `update with one-to-many writes upserts matching ids, inserts new ones, and hard-deletes ids absent from the incoming list`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val removedId = UUID.randomUUID()
            val newId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    // The "current child ids" SELECT resyncOneToManyElements
                    // issues - distinct from the final re-select's own `rows`.
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("id" to existingId)),
                            MapRowContext(mapOf("id" to removedId)),
                        ),
                )
            val access = updateAccess(executor, withOneToManyWrites = true)

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes =
                        listOf(
                            UpdatePlaysetNote(existingId, "updated text"),
                            UpdatePlaysetNote(newId, "brand new"),
                        ),
                ),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT + 1 child UPDATE + 1
            // child INSERT + 1 child DELETE + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(6)

            val currentIdsSelect = executor.allQueries[1]
            assertThat(currentIdsSelect.sql.lowercase().startsWith("select")).isTrue()
            assertThat(currentIdsSelect.sql.contains("playset_note", ignoreCase = true)).isTrue()

            val childUpdate = executor.allQueries[2]
            assertThat(childUpdate.sql.lowercase().startsWith("update")).isTrue()
            assertThat(childUpdate.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(childUpdate.bindValues.contains("updated text")).isTrue()
            assertThat(childUpdate.bindValues.contains(existingId)).isTrue()

            val childInsert = executor.allQueries[3]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(newId)).isTrue()
            assertThat(childInsert.bindValues.contains("brand new")).isTrue()

            val childDelete = executor.allQueries[4]
            assertThat(childDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(childDelete.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(childDelete.bindValues.contains(removedId)).isTrue()
        }
    }

    private fun updateAccessWithNestedOwnedChild(executor: FakeQueryExecutor) =
        object : JooqUpdateAccess<String, UpdatePlayset, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val updateMapper =
                UpdateMapper<UpdatePlayset, UpdateValues> { cmd ->
                    UpdateValues(PLAYSET.NAME to cmd.name)
                }

            override val idColumn = PLAYSET.ID
            override val versionColumn = PLAYSET.VERSION
            override val versionIncrementStrategy =
                VersionIncrementStrategy { column ->
                    DSL.field("{0} + 1", column.dataType, column)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val updateOneToManyWrites =
                listOf(
                    OneToManyUpdateWrite(
                        childTable = PLAYSET_NOTE,
                        parentColumn = PLAYSET_NOTE.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        childIdentityColumn = PLAYSET_NOTE.ID,
                        elements = { cmd: UpdatePlayset -> cmd.notes },
                        elementId = { it.id },
                        elementMapper =
                            UpdateMapper<UpdatePlaysetNote, UpdateValues> { cmd ->
                                UpdateValues(PLAYSET_NOTE.TEXT to cmd.text)
                            },
                        elementOwnedChildWrites =
                            listOf(
                                OwnedChildDeleteWrite(
                                    childTable = PLAYSET_GAME,
                                    childIdColumn = PLAYSET_GAME.ID,
                                    parentFkColumn = PLAYSET_NOTE.GAME_ID,
                                ),
                            ),
                    ),
                )
        }

    @Test
    fun `update with a removed managed-group element cascades into deleting its own owned child row, only after the element's own row is removed`() {
        runBlocking {
            val removedId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    allRowsSequence =
                        listOf(
                            // 1: resyncOneToManyElements' own "current ids"
                            // SELECT - one existing note, and it's absent
                            // from the incoming (empty) notes list.
                            listOf(MapRowContext(mapOf("id" to removedId))),
                            // 2: readOwnedChildIdsFor's own FK-value SELECT,
                            // reading the about-to-be-removed note's own
                            // game_id before it's deleted.
                            listOf(MapRowContext(mapOf("game_id" to 42L))),
                        ),
                )
            val access = updateAccessWithNestedOwnedChild(executor)

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", notes = emptyList()),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT + 1 owned-child FK
            // SELECT + 1 note DELETE + 1 owned-child (game) DELETE + 1
            // re-select.
            assertThat(executor.allQueries.size).isEqualTo(6)
            val ownedChildSelect = executor.allQueries[2]
            assertThat(ownedChildSelect.sql.lowercase().startsWith("select")).isTrue()
            assertThat(ownedChildSelect.sql.contains("game_id", ignoreCase = true)).isTrue()
            val noteDelete = executor.allQueries[3]
            assertThat(noteDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(noteDelete.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(noteDelete.bindValues.contains(removedId)).isTrue()
            val gameDelete = executor.allQueries[4]
            assertThat(gameDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(gameDelete.sql.contains("game", ignoreCase = true)).isTrue()
            assertThat(gameDelete.bindValues.contains(42L)).isTrue()
        }
    }

    /**
     * The insert-side counterpart of [updateAccessWithNestedOwnedChild] - reuses
     * [CreatePlaysetNoteWithOwnedGame]/[CreateGameFixture] (the exact same element/child command
     * types the create-side [createAccessWithNestedOwnedChild] fixture uses) as the *update*
     * command's own element type too, matching the real-world shape this covers: a `REPLACE_ALL`
     * managed group with no separate `Update...` child command at all - every element written
     * during `update()` is exactly as fresh as one written during `create()`.
     */
    private data class UpdatePlaysetWithOwnedGameNotes(
        val name: String,
        val notes: List<CreatePlaysetNoteWithOwnedGame> = emptyList(),
    )

    private fun updateAccessWithNestedOwnedChildCreate(
        executor: FakeQueryExecutor,
        replaceAll: Boolean = false,
    ) = object : JooqUpdateAccess<String, UpdatePlaysetWithOwnedGameNotes, PlaysetEntity> {

        override val dsl = this@JooqAccessTest.dsl
        override val table = PLAYSET
        override val rowMapper = this@JooqAccessTest.rowMapper
        override val queryExecutor = executor
        override val updateMapper =
            UpdateMapper<UpdatePlaysetWithOwnedGameNotes, UpdateValues> { cmd ->
                UpdateValues(PLAYSET.NAME to cmd.name)
            }

        override val idColumn = PLAYSET.ID
        override val versionColumn = PLAYSET.VERSION
        override val versionIncrementStrategy =
            VersionIncrementStrategy { column ->
                DSL.field("{0} + 1", column.dataType, column)
            }

        override val fieldMapping = mapping
        override val queryTranslator = JooqSearchQueryTranslator()
        override val idField = this@JooqAccessTest.idField
        override val transactionalExecutor = FakeTransactionalExecutor(executor)
        override val updateOneToManyWrites =
            listOf(
                OneToManyUpdateWrite(
                    childTable = PLAYSET_NOTE,
                    parentColumn = PLAYSET_NOTE.PLAYSET_ID,
                    parentRootColumn = PLAYSET.ID,
                    childIdentityColumn = PLAYSET_NOTE.ID,
                    elements = { cmd: UpdatePlaysetWithOwnedGameNotes -> cmd.notes },
                    elementId = { it.id },
                    elementMapper =
                        UpdateMapper<CreatePlaysetNoteWithOwnedGame, UpdateValues> { cmd ->
                            UpdateValues(PLAYSET_NOTE.TEXT to cmd.text)
                        },
                    elementOwnedChildCreateWrites =
                        listOf(
                            OwnedChildCreateWrite<
                                CreatePlaysetNoteWithOwnedGame,
                                CreateGameFixture,
                            >(
                                childCommand = { it.game },
                                childMapper =
                                    CreateMapper<CreateGameFixture, CreateValues> { game ->
                                        CreateValues(PLAYSET_GAME.PUBLIC_ID to game.publicId)
                                    },
                                childTable = PLAYSET_GAME,
                                childIdColumn = PLAYSET_GAME.ID,
                                parentFkColumn = PLAYSET_NOTE.GAME_ID,
                            ),
                        ),
                    replaceAll = replaceAll,
                ),
            )
    }

    @Test
    fun `update with a newly-added managed-group element creates its own owned child row before the element's own batch INSERT, folding its newly-generated id into that same row`() {
        runBlocking {
            val rootRow = playsetRow("abc", "Catan", 2L)
            val gameRow = MapRowContext(mapOf("id" to 42L))
            val executor =
                FakeQueryExecutor(
                    rows = listOf(rootRow),
                    returningRows = listOf(rootRow, gameRow),
                    resolveRows = emptyList(),
                )
            val access = updateAccessWithNestedOwnedChildCreate(executor)
            val publicGameId = UUID.randomUUID()
            val newNote =
                CreatePlaysetNoteWithOwnedGame(
                    id = UUID.randomUUID(),
                    text = "brand new",
                    game = CreateGameFixture(publicId = publicGameId),
                )

            access.update(
                "abc",
                UpdatePlaysetWithOwnedGameNotes(name = "Catan", notes = listOf(newNote)),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT (empty - no existing
            // notes, so the incoming note is new) + 1 owned-child
            // (PLAYSET_GAME)
            // INSERT + 1 note batch INSERT + 1 re-select.
            assertThat(executor.allQueries.size).isEqualTo(5)
            val currentIdsSelect = executor.allQueries[1]
            assertThat(currentIdsSelect.sql.lowercase().startsWith("select")).isTrue()
            val childInsert = executor.allQueries[2]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.sql.contains("game", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(publicGameId)).isTrue()
            val noteInsert = executor.allQueries[3]
            assertThat(noteInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(noteInsert.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(noteInsert.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(noteInsert.bindValues.contains(42L)).isTrue()
            assertThat(noteInsert.bindValues.contains(newNote.text)).isTrue()
        }
    }

    @Test
    fun `update with replaceAll creates a fresh owned child row for every incoming element too, since every element goes through the insert path`() {
        runBlocking {
            val existingNoteId = UUID.randomUUID()
            val rootRow = playsetRow("abc", "Catan", 2L)
            val gameRow = MapRowContext(mapOf("id" to 42L))
            val executor =
                FakeQueryExecutor(
                    rows = listOf(rootRow),
                    returningRows = listOf(rootRow, gameRow),
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingNoteId))),
                )
            val access = updateAccessWithNestedOwnedChildCreate(executor, replaceAll = true)
            val publicGameId = UUID.randomUUID()
            val newNote =
                CreatePlaysetNoteWithOwnedGame(
                    id = UUID.randomUUID(),
                    text = "brand new",
                    game = CreateGameFixture(publicId = publicGameId),
                )

            access.update(
                "abc",
                UpdatePlaysetWithOwnedGameNotes(name = "Catan", notes = listOf(newNote)),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT (one existing note) + 1
            // existing note DELETE (replaceAll removes it first, regardless of
            // no id collision) + 1 owned-child (PLAYSET_GAME) INSERT + 1 note
            // batch INSERT + 1 re-select.
            assertThat(executor.allQueries.size).isEqualTo(6)
            val noteDelete = executor.allQueries[2]
            assertThat(noteDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(noteDelete.bindValues.contains(existingNoteId)).isTrue()
            val childInsert = executor.allQueries[3]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.bindValues.contains(publicGameId)).isTrue()
            val noteInsert = executor.allQueries[4]
            assertThat(noteInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(noteInsert.bindValues.contains(42L)).isTrue()
        }
    }

    @Test
    fun `update under the upsert diff starts a new child row at the initial version and leaves a matched row's version alone`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val newId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingId))),
                )
            val access =
                updateAccess(executor, withOneToManyWrites = true, withOneToManyVersion = true)

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes =
                        listOf(
                            UpdatePlaysetNote(existingId, "updated text"),
                            UpdatePlaysetNote(newId, "brand new"),
                        ),
                ),
                expectedVersion = 1L,
            )

            val childUpdate = executor.allQueries[2]
            assertThat(childUpdate.sql.lowercase()).startsWith("update").contains("playset_note")
            assertThat(childUpdate.sql.lowercase()).doesNotContain("version")
            val childInsert = executor.allQueries[3]
            assertThat(childInsert.sql.lowercase()).startsWith("insert").contains("\"version\"")
            assertThat(childInsert.bindValues).contains(newId, 7L)
        }
    }

    @Test
    fun `update with replaceAll starts every inserted child row at the initial version`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to UUID.randomUUID()))),
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyReplaceAll = true,
                    withOneToManyVersion = true,
                )

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes = listOf(UpdatePlaysetNote(UUID.randomUUID(), "new")),
                ),
                expectedVersion = 1L,
            )

            val childInsert = executor.allQueries.single { it.sql.lowercase().startsWith("insert") }

            assertThat(childInsert.sql.lowercase()).contains("\"version\"")
            assertThat(childInsert.bindValues).contains(7L)
        }
    }

    @Test
    fun `update with replaceAll removes every existing row and inserts every incoming one fresh, even when an incoming id happens to match an existing row's`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val otherExistingId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("id" to existingId)),
                            MapRowContext(mapOf("id" to otherExistingId)),
                        ),
                )
            val access =
                updateAccess(executor, withOneToManyWrites = true, withOneToManyReplaceAll = true)

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    // Deliberately reuses `existingId` - proves replaceAll
                    // ignores id matching entirely, unlike the ordinary
                    // diff-based upsert (which would UPDATE this row instead
                    // of removing and reinserting it).
                    notes = listOf(UpdatePlaysetNote(existingId, "brand new")),
                ),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT + 1 child DELETE (every
            // existing row, both ids - not just the one absent from the
            // incoming list) + 1 child INSERT (no child UPDATE at all -
            // matchedIndices is always empty under replaceAll) + 1 re-select
            // SELECT. The DELETE runs first: the reused id would otherwise
            // collide with the row it replaces.
            assertThat(executor.allQueries.size).isEqualTo(5)

            val currentIdsSelect = executor.allQueries[1]
            assertThat(currentIdsSelect.sql.lowercase().startsWith("select")).isTrue()

            val childDelete = executor.allQueries[2]
            assertThat(childDelete.sql.lowercase().startsWith("delete")).isTrue()

            val childInsert = executor.allQueries[3]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.bindValues.contains(existingId)).isTrue()
            assertThat(childInsert.bindValues.contains("brand new")).isTrue()
            assertThat(childDelete.bindValues.contains(existingId)).isTrue()
            assertThat(childDelete.bindValues.contains(otherExistingId)).isTrue()
        }
    }

    @Test
    fun `update with replaceAll and a softDeleteColumn soft-deletes every existing row instead of hard-deleting it`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val newId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingId))),
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyReplaceAll = true,
                    withOneToManySoftDelete = true,
                )

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes = listOf(UpdatePlaysetNote(newId, "brand new")),
                ),
                expectedVersion = 1L,
            )

            val childDelete = executor.allQueries[2]
            assertThat(childDelete.sql.lowercase().startsWith("update")).isTrue()
            assertThat(childDelete.sql.contains("deleted_at", ignoreCase = true)).isTrue()
            assertThat(childDelete.bindValues.contains(existingId)).isTrue()
        }
    }

    @Test
    fun `update with replaceAll on the top-level group cascades into a nested group's own removal for the old rows without the nested group declaring replaceAll itself`() {
        runBlocking {
            val existingNoteId = UUID.randomUUID()
            val newNoteId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingNoteId))),
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyReplaceAll = true,
                    withNestedOneToManyWrites = true,
                )
            val newTag = UpdateNoteTag(UUID.randomUUID(), "fresh")

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes =
                        listOf(
                            UpdatePlaysetNote(newNoteId, "brand new note", tags = listOf(newTag)),
                        ),
                ),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 note-level "current ids" SELECT + 1 note
            // INSERT + 1 tag-level "current ids" SELECT (scoped to the
            // brand-new note id - always empty, so every incoming tag is
            // its own fresh INSERT) + 1 tag INSERT + 1 note-level DELETE
            // (cascading the old note's own tags first, then the note
            // itself - no separate old tags found here since the tag-level
            // SELECT above already returned nothing for the new note id,
            // and cascadeRemoveElementWrites issues its own SELECT scoped to
            // the *removed* note id) + 1 nested tag DELETE (cascaded, for
            // the old note's own tags) + 1 re-select SELECT.
            val sqls = executor.allQueries.map { it.sql.lowercase() }

            val noteInsert =
                executor.allQueries.first {
                    it.sql.lowercase().startsWith("insert") &&
                        it.sql.contains("playset_note", ignoreCase = true) &&
                        !it.sql.contains("playset_note_tag", ignoreCase = true)
                }

            assertThat(noteInsert.bindValues.contains(newNoteId)).isTrue()

            val tagInsert =
                executor.allQueries.first {
                    it.sql.lowercase().startsWith("insert") &&
                        it.sql.contains("playset_note_tag", ignoreCase = true)
                }

            // The tag's own INSERT binds the brand-new note's own id as its
            // parentColumn value - proof the nested recursion used the
            // freshly-generated parent id, not the old, now-removed one.
            assertThat(tagInsert.bindValues.contains(newNoteId)).isTrue()
            assertThat(tagInsert.bindValues.contains(newTag.id)).isTrue()

            val noteDelete =
                executor.allQueries.first {
                    it.sql.lowercase().startsWith("delete") &&
                        it.sql.contains("playset_note", ignoreCase = true) &&
                        !it.sql.contains("playset_note_tag", ignoreCase = true)
                }

            assertThat(noteDelete.bindValues.contains(existingNoteId)).isTrue()
            assertThat(sqls.any { it.startsWith("delete") }).isTrue()
        }
    }

    @Test
    fun `update with a managed group nested inside another managed group's own child resyncs the grandchild rows right alongside their own matched parent, using its own elementId`() {
        runBlocking {
            val existingNoteId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    // The same "current ids" rows answer both the note-level
                    // and the tag-level diff SELECT (FakeQueryExecutor has no
                    // per-call sequencing) - both incoming tags' own random
                    // ids are guaranteed not to match existingNoteId, so both
                    // still resolve as "new" for the tag-level diff, exactly
                    // like a genuinely empty existing-tags set would. This
                    // does mean the tag-level diff also (harmlessly) treats
                    // "existingNoteId" itself as one *removed* tag id (it's
                    // present in this shared resolveRows answer, but absent
                    // from tag1/tag2's own incoming ids) - a fake-executor
                    // artifact, not real behavior, so it's accounted for
                    // below rather than avoided.
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingNoteId))),
                    // 1 matched note (the note-level batch UPDATE), then 2
                    // unmatched tags (the tag-level batch INSERT, both
                    // siblings folded into a single statement).
                    updateRowCounts = listOf(1, 2),
                )
            val access =
                updateAccess(executor, withOneToManyWrites = true, withNestedOneToManyWrites = true)
            val tag1 = UpdateNoteTag(UUID.randomUUID(), "urgent")
            val tag2 = UpdateNoteTag(UUID.randomUUID(), "later")
            val note = UpdatePlaysetNote(existingNoteId, "kept", tags = listOf(tag1, tag2))

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", notes = listOf(note)),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current note ids" SELECT + 1 note UPDATE +
            // 1 "current tag ids" SELECT + 1 tag batch INSERT (both
            // unmatched tags, one statement) + 1 tag DELETE (the
            // fake-executor artifact noted above) + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(7)
            val tagInsert =
                executor.allQueries.single {
                    it.sql.lowercase().startsWith("insert") &&
                        it.sql.contains("playset_note_tag", ignoreCase = true)
                }

            assertThat(tagInsert.bindValues.contains(existingNoteId)).isTrue()
            assertThat(tagInsert.bindValues.contains(tag1.label)).isTrue()
            assertThat(tagInsert.bindValues.contains(tag2.label)).isTrue()
        }
    }

    @Test
    fun `update with a managed group nested inside another managed group's own child hard-deletes the grandchild rows before removing a note absent from the incoming list`() {
        runBlocking {
            val removedNoteId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to removedNoteId))),
                )
            val access =
                updateAccess(executor, withOneToManyWrites = true, withNestedOneToManyWrites = true)

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", notes = emptyList()),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current note ids" SELECT + 1 tag cascade
            // DELETE (before the note itself, no further nesting so no
            // extra "current ids" SELECT for the tags) + 1 note DELETE + 1
            // re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(5)
            val tagCascadeDelete = executor.allQueries[2]
            assertThat(tagCascadeDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(tagCascadeDelete.sql.contains("playset_note_tag", ignoreCase = true))
                .isTrue()
            assertThat(tagCascadeDelete.bindValues.contains(removedNoteId)).isTrue()
            val noteDelete = executor.allQueries[3]
            assertThat(noteDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(
                noteDelete.sql.contains("playset_note", ignoreCase = true) &&
                    !noteDelete.sql.contains("playset_note_tag", ignoreCase = true),
            ).isTrue()
            assertThat(noteDelete.bindValues.contains(removedNoteId)).isTrue()
        }
    }

    @Test
    fun `update with a managed group nested inside another managed group's own child soft-deletes the grandchild rows before soft-deleting a note absent from the incoming list, when both have a softDeleteColumn`() {
        runBlocking {
            val removedNoteId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to removedNoteId))),
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManySoftDelete = true,
                    withNestedOneToManyWrites = true,
                    withNestedOneToManySoftDelete = true,
                )

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", notes = emptyList()),
                expectedVersion = 1L,
            )

            assertThat(executor.allQueries.size).isEqualTo(5)
            val tagCascadeSoftDelete = executor.allQueries[2]
            assertThat(tagCascadeSoftDelete.sql.lowercase().startsWith("update")).isTrue()
            assertThat(tagCascadeSoftDelete.sql.contains("playset_note_tag", ignoreCase = true))
                .isTrue()
            assertThat(tagCascadeSoftDelete.sql.contains("deleted_at", ignoreCase = true)).isTrue()
            assertThat(tagCascadeSoftDelete.bindValues.contains(removedNoteId)).isTrue()
            val noteSoftDelete = executor.allQueries[3]
            assertThat(noteSoftDelete.sql.lowercase().startsWith("update")).isTrue()
            assertThat(
                noteSoftDelete.sql.contains("playset_note", ignoreCase = true) &&
                    !noteSoftDelete.sql.contains("playset_note_tag", ignoreCase = true),
            ).isTrue()
        }
    }

    @Test
    fun `update with one-to-many writes soft-deletes absent ids when softDeleteColumn is set, instead of hard-deleting`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val removedId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("id" to existingId)),
                            MapRowContext(mapOf("id" to removedId)),
                        ),
                )
            val access =
                updateAccess(executor, withOneToManyWrites = true, withOneToManySoftDelete = true)

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes = listOf(UpdatePlaysetNote(existingId, "kept")),
                ),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT + 1 child UPDATE (kept)
            // + 1 soft-delete UPDATE (removed) + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(5)

            val softDelete = executor.allQueries[3]
            assertThat(softDelete.sql.lowercase().startsWith("update")).isTrue()
            assertThat(softDelete.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(softDelete.sql.contains("deleted_at", ignoreCase = true)).isTrue()
            assertThat(softDelete.bindValues.contains(removedId)).isTrue()
        }
    }

    @Test
    fun `update with one-to-many writes resolves a nested group's identity-field FK via one batched SELECT, for both a matched and a brand-new element`() {
        runBlocking {
            val publicGameId1 = UUID.randomUUID()
            val publicGameId2 = UUID.randomUUID()
            val rawGameId1 = 42L
            val rawGameId2 = 43L
            val existingId = UUID.randomUUID()
            val newId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    allRowsSequence =
                        listOf(
                            // Call #1: the "current child ids" diff SELECT.
                            listOf(MapRowContext(mapOf("id" to existingId))),
                            // Call #2: the batched FK-resolve SELECT,
                            // covering both the matched and the new
                            // element's own lookup value in one round trip.
                            listOf(
                                MapRowContext(
                                    mapOf("id" to rawGameId1, "public_id" to publicGameId1),
                                ),
                                MapRowContext(
                                    mapOf("id" to rawGameId2, "public_id" to publicGameId2),
                                ),
                            ),
                        ),
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyElementFkResolve = true,
                )

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes =
                        listOf(
                            UpdatePlaysetNote(existingId, "updated text", publicGameId1),
                            UpdatePlaysetNote(newId, "brand new", publicGameId2),
                        ),
                ),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" SELECT + 1 batched FK-resolve
            // SELECT (both elements' lookup values in one IN-clause) + 1
            // child batch UPDATE (matched, the resolved raw id substituted
            // directly) + 1 child batch INSERT (new, same) + 1 re-select
            // SELECT.
            assertThat(executor.allQueries.size).isEqualTo(6)

            val resolveSelect = executor.allQueries[2]
            assertThat(resolveSelect.sql.lowercase().startsWith("select")).isTrue()
            assertThat(resolveSelect.bindValues.contains(publicGameId1)).isTrue()
            assertThat(resolveSelect.bindValues.contains(publicGameId2)).isTrue()

            val childUpdate = executor.allQueries[3]
            assertThat(childUpdate.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(childUpdate.bindValues.contains(rawGameId1)).isTrue()

            val childInsert = executor.allQueries[4]
            assertThat(childInsert.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(rawGameId2)).isTrue()
        }
    }

    @Test
    fun `update with one-to-many writes throws ReferencedEntityNotFoundException when a nested group's FK lookup finds no row`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingId))),
                    updateRowCount = 0,
                    existsResult = { false },
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyElementFkResolve = true,
                )

            assertThatThrownBy {
                runBlocking {
                    access.update(
                        "abc",
                        UpdatePlayset(
                            name = "Catan",
                            notes =
                                listOf(
                                    UpdatePlaysetNote(
                                        existingId,
                                        "updated text",
                                        UUID.randomUUID(),
                                    ),
                                ),
                        ),
                        expectedVersion = 1L,
                    )
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
        }
    }

    @Test
    fun `update with one-to-many writes binds a nested group's FK column to null directly for an element whose valueSupplier returns null, no batched SELECT, no exception`() {
        runBlocking {
            val existingId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    // Only the "current child ids" diff SELECT - the
                    // FK-resolve SELECT is skipped entirely, no lookup value
                    // to resolve.
                    resolveRows = listOf(MapRowContext(mapOf("id" to existingId))),
                    updateRowCounts = listOf(1),
                )
            val access =
                updateAccess(
                    executor,
                    withOneToManyWrites = true,
                    withOneToManyElementFkResolve = true,
                )

            access.update(
                "abc",
                UpdatePlayset(
                    name = "Catan",
                    notes = listOf(UpdatePlaysetNote(existingId, "updated text", gameId = null)),
                ),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 "current ids" diff SELECT + 1 child batch
            // UPDATE + 1 re-select SELECT - no FK-resolve SELECT.
            assertThat(executor.allQueries.size).isEqualTo(4)
            val childUpdate = executor.allQueries[2]
            assertThat(childUpdate.sql.lowercase().startsWith("update")).isTrue()
            assertThat(childUpdate.sql.contains("game_id", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `update with a JunctionTargetResolve resolves supplied public ids to raw ids before writing`() {
        runBlocking {
            // PLAYSET_EXPANSION.EXPANSION_ID is UUID-typed in this fixture,
            // so the resolved "raw" id must be a real UUID too - jOOQ
            // enforces the bound value's Kotlin type against the target
            // column's own declared type at query-build time.
            val publicId1 = UUID.randomUUID()
            val publicId2 = UUID.randomUUID()
            val rawId1 = UUID.randomUUID()
            val rawId2 = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("id" to rawId1, "public_id" to publicId1)),
                            MapRowContext(mapOf("id" to rawId2, "public_id" to publicId2)),
                        ),
                )
            val access =
                updateAccess(
                    executor,
                    withJunctionWrites = true,
                    targetResolve = JunctionTargetResolve(EXPANSION.PUBLIC_ID, EXPANSION.ID),
                )

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", expansionIds = setOf(publicId1, publicId2)),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 junction DELETE + 1 resolve SELECT + 1
            // batch junction INSERT (both resolved ids in one statement) +
            // 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(5)
            val resolveQuery = executor.allQueries[2]
            assertThat(resolveQuery.sql.lowercase().startsWith("select")).isTrue()
            assertThat(resolveQuery.sql.contains("expansion", ignoreCase = true)).isTrue()

            val junctionInsert = executor.allQueries[3]
            assertThat(junctionInsert.bindValues.contains(rawId1)).isTrue()
            assertThat(junctionInsert.bindValues.contains(rawId2)).isTrue()
            assertThat(!junctionInsert.bindValues.contains(publicId1)).isTrue()
            assertThat(!junctionInsert.bindValues.contains(publicId2)).isTrue()
        }
    }

    @Test
    fun `update throws ReferencedEntityNotFoundException when a JunctionTargetResolve lookup finds no matching row`() {
        runBlocking {
            val publicId1 = UUID.randomUUID()
            val missingPublicId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "Catan", 2L)),
                    resolveRows =
                        listOf(
                            MapRowContext(
                                mapOf("id" to UUID.randomUUID(), "public_id" to publicId1),
                            ),
                        ),
                )
            val access =
                updateAccess(
                    executor,
                    withJunctionWrites = true,
                    targetResolve = JunctionTargetResolve(EXPANSION.PUBLIC_ID, EXPANSION.ID),
                )

            assertThatThrownBy {
                runBlocking {
                    access.update(
                        "abc",
                        UpdatePlayset(
                            name = "Catan",
                            expansionIds = setOf(publicId1, missingPublicId),
                        ),
                        expectedVersion = 1L,
                    )
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
        }
    }

    @Test
    fun `update writes a junction's own parent-root column value, not the caller-supplied id`() {
        runBlocking {
            // A real reported bug: resyncJunctionRows used to bind
            // mapId(id) - the caller-supplied id - directly as the
            // junction's root value for both the DELETE ... WHERE and the
            // re-INSERT, instead of reading the junction's own declared
            // root(from = ...) column back from the row. The passed-in id
            // ("caller-id") is deliberately different from the row's own
            // "__junctionRoot_id" value, so a correct fix's DELETE/INSERT
            // must use the latter, never the former.
            val internalId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(
                                mapOf(
                                    "id" to "caller-id",
                                    "name" to "Catan",
                                    "version" to 2L,
                                    "__junctionRoot_id" to internalId,
                                ),
                            ),
                        ),
                )
            val access = updateAccess(executor, withJunctionWrites = true)

            access.update(
                "caller-id",
                UpdatePlayset(name = "Catan", expansionIds = setOf(UUID.randomUUID())),
                expectedVersion = 1L,
            )

            val deleteQuery = executor.allQueries[1]
            assertThat(deleteQuery.bindValues.contains(internalId)).isTrue()
            assertThat(!deleteQuery.bindValues.contains("caller-id")).isTrue()
            val insertQuery = executor.allQueries[2]
            assertThat(insertQuery.bindValues.contains(internalId)).isTrue()
            assertThat(!insertQuery.bindValues.contains("caller-id")).isTrue()
        }
    }

    @Test
    fun `update with junction writes still deletes existing rows even with an empty target set`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 2L)))
            val access = updateAccess(executor, withJunctionWrites = true)

            access.update(
                "abc",
                UpdatePlayset(name = "Catan", expansionIds = emptySet()),
                expectedVersion = 1L,
            )

            // 1 root UPDATE + 1 junction DELETE + 1 re-select SELECT, no
            // INSERT since the new set is empty.
            assertThat(executor.allQueries.size).isEqualTo(3)
        }
    }

    /**
     * Hand-written stand-in for what a generated `PatchField`-aware `UpdatePlayset` will look like
     * once gradle-plugin codegen supports it - see [UpdateCommandPatchFieldResolver] (not yet
     * implemented).
     */
    private data class UpdatePlaysetPatch(
        val name: PatchField<String> = PatchField.Unset,
        val expansionIds: PatchField<Set<UUID>> = PatchField.Unset,
    )

    private fun updateAccessPatch(
        executor: FakeQueryExecutor,
        withJunctionWrites: Boolean = false,
    ) = object : JooqUpdateAccess<String, UpdatePlaysetPatch, PlaysetEntity> {

        override val dsl = this@JooqAccessTest.dsl
        override val table = PLAYSET
        override val rowMapper = this@JooqAccessTest.rowMapper
        override val queryExecutor = executor
        override val updateMapper =
            UpdateMapper<UpdatePlaysetPatch, UpdateValues> { cmd ->
                UpdateValues(listOfNotNull(cmd.name.toUpdateEntry(PLAYSET.NAME)))
            }

        override val idColumn = PLAYSET.ID
        override val versionColumn: TableField<*, *>? = null
        override val fieldMapping = mapping
        override val queryTranslator = JooqSearchQueryTranslator()
        override val idField = this@JooqAccessTest.idField
        override val transactionalExecutor = FakeTransactionalExecutor(executor)
        override val updateJunctionWrites =
            if (withJunctionWrites) {
                listOf(
                    JunctionWrite(
                        table = PLAYSET_EXPANSION,
                        rootColumn = PLAYSET_EXPANSION.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        targetColumn = PLAYSET_EXPANSION.EXPANSION_ID,
                        mapper =
                            JunctionWriteMapper<UpdatePlaysetPatch> {
                                it.expansionIds.toTargetIdsOrNull()
                            },
                    ),
                )
            } else {
                emptyList()
            }
    }

    @Test
    fun `update with PatchField Unset leaves the column out of the SET clause`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 0L)))
            val access = updateAccessPatch(executor)

            access.update(
                "abc",
                UpdatePlaysetPatch(name = PatchField.Unset),
                expectedVersion = null,
            )

            // WHERE id=? only - the SET clause has nothing bound. The UPDATE
            // is the first query.
            assertThat(
                executor.allQueries
                    .first()
                    .bindValues.size,
            ).isEqualTo(1)
        }
    }

    @Test
    fun `update with PatchField Value writes the provided column`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan (2nd ed)", 0L)))
            val access = updateAccessPatch(executor)

            access.update(
                "abc",
                UpdatePlaysetPatch(name = PatchField.Value("Catan (2nd ed)")),
                expectedVersion = null,
            )

            // SET name=? WHERE id=? - both bound.
            assertThat(
                executor.allQueries
                    .first()
                    .bindValues.size,
            ).isEqualTo(2)
        }
    }

    @Test
    fun `update with a junction left Unset touches no junction rows at all`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 0L)))
            val access = updateAccessPatch(executor, withJunctionWrites = true)

            access.update(
                "abc",
                UpdatePlaysetPatch(name = PatchField.Unset, expansionIds = PatchField.Unset),
                expectedVersion = null,
            )

            // root UPDATE + re-select SELECT only - no junction DELETE, no
            // junction INSERT.
            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `update with a junction PatchField Value still resyncs as usual`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("abc", "Catan", 0L)))
            val access = updateAccessPatch(executor, withJunctionWrites = true)
            val expansionIds = setOf(UUID.randomUUID())

            access.update(
                "abc",
                UpdatePlaysetPatch(expansionIds = PatchField.Value(expansionIds)),
                expectedVersion = null,
            )

            // 1 root UPDATE + 1 junction DELETE + 1 junction INSERT + 1
            // re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(4)
        }
    }

    @Test
    fun `create and update re-select through the same aliased projection findById uses, not the raw RETURNING row`() {
        runBlocking {
            // A field bound to a differently-named physical column
            // (raw column "name", search-facing key stays "name" here too,
            // but exercised through the exact same translateById-based path
            // findById uses) proves the re-select - not the raw
            // INSERT/UPDATE ... RETURNING row - is what ends up in the
            // mapped result. FakeQueryExecutor.rows is what a re-select
            // (executeAndGetFirst) reads from, while returningRow /
            // rows.firstOrNull() covers the INSERT/UPDATE's own RETURNING
            // check - here they intentionally differ, so the assertion only
            // passes if the create/update path really does a second SELECT.
            val insertReturning = playsetRow("new-id", "stale-raw-name", 0L)
            val reselected = playsetRow("new-id", "Wingspan", 0L)
            val executor =
                FakeQueryExecutor(rows = listOf(reselected), returningRow = insertReturning)
            val access = createAccess(executor)

            val result = access.create(CreatePlayset(name = "Wingspan"))

            assertThat(result).isEqualTo(PlaysetEntity("new-id", "Wingspan", 0L))
        }
    }

    /**
     * A 1:1 group's identity-field create/update binding (`gameId`) - a public id (`UUID`), needing
     * a lookup query against [PLAYSET_GAME] to resolve the actual FK value (`PLAYSET_GAME.ID`, a
     * `Long`) written to [PLAYSET.GAME_ID].
     */
    private data class CreatePlaysetWithGame(
        val name: String,
        val gameId: UUID,
    )

    private data class UpdatePlaysetWithGame(
        val gameId: UUID,
    )

    private data class CreatePlaysetWithGameAndOwner(
        val name: String,
        val gameId: UUID,
        val ownerId: UUID,
    )

    private data class UpdatePlaysetWithGameAndOwner(
        val gameId: UUID,
        val ownerId: UUID,
    )

    private fun createAccessWithFkResolve(executor: FakeQueryExecutor) =
        object : JooqCreateAccess<CreatePlaysetWithGame, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val createMapper =
                CreateMapper<CreatePlaysetWithGame, CreateValues> { cmd ->
                    CreateValues(PLAYSET.NAME to cmd.name)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

            override val createFkResolves =
                listOf(
                    GroupFkResolve<CreatePlaysetWithGame>(
                        fkColumn = PLAYSET.GAME_ID,
                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                        targetColumn = PLAYSET_GAME.ID,
                        valueSupplier = { it.gameId },
                    ),
                )
        }

    private fun updateAccessWithFkResolve(executor: FakeQueryExecutor) =
        object : JooqUpdateAccess<String, UpdatePlaysetWithGame, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val updateMapper =
                UpdateMapper<UpdatePlaysetWithGame, UpdateValues> { UpdateValues() }

            override val idColumn = PLAYSET.ID
            override val versionColumn: TableField<*, *>? = null
            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val updateFkResolves =
                listOf(
                    GroupFkResolve<UpdatePlaysetWithGame>(
                        fkColumn = PLAYSET.GAME_ID,
                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                        targetColumn = PLAYSET_GAME.ID,
                        valueSupplier = { it.gameId },
                    ),
                )
        }

    /**
     * Same shape as [CreatePlaysetWithGame]/[UpdatePlaysetWithGame], but for an *optional* 1:1
     * group - `gameId` is nullable, so `valueSupplier` can return `null` (see
     * `GroupFkResolve.valueSupplier`'s own kdoc).
     */
    private data class CreatePlaysetWithNullableGame(
        val name: String,
        val gameId: UUID?,
    )

    private data class UpdatePlaysetWithNullableGame(
        val gameId: UUID?,
    )

    private fun createAccessWithNullableFkResolve(executor: FakeQueryExecutor) =
        object : JooqCreateAccess<CreatePlaysetWithNullableGame, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val createMapper =
                CreateMapper<CreatePlaysetWithNullableGame, CreateValues> { cmd ->
                    CreateValues(PLAYSET.NAME to cmd.name)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

            override val createFkResolves =
                listOf(
                    GroupFkResolve<CreatePlaysetWithNullableGame>(
                        fkColumn = PLAYSET.GAME_ID,
                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                        targetColumn = PLAYSET_GAME.ID,
                        valueSupplier = { it.gameId },
                    ),
                )
        }

    private fun updateAccessWithNullableFkResolve(executor: FakeQueryExecutor) =
        object : JooqUpdateAccess<String, UpdatePlaysetWithNullableGame, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val updateMapper =
                UpdateMapper<UpdatePlaysetWithNullableGame, UpdateValues> { UpdateValues() }

            override val idColumn = PLAYSET.ID
            override val versionColumn: TableField<*, *>? = null
            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val updateFkResolves =
                listOf(
                    GroupFkResolve<UpdatePlaysetWithNullableGame>(
                        fkColumn = PLAYSET.GAME_ID,
                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                        targetColumn = PLAYSET_GAME.ID,
                        valueSupplier = { it.gameId },
                    ),
                )
        }

    /**
     * A plain 1:1 group whose *update*-side binding is `PatchField<UUID>`-typed (patch semantics:
     * [PatchField.Unset] means "the caller didn't mention this relation at all", distinct from a
     * resolved `null`) - see [GroupFkResolve.skip]'s own kdoc for why this needs its own signal,
     * separate from [GroupFkResolve.valueSupplier] returning `null`.
     */
    private data class UpdatePlaysetWithPatchGame(
        val gameId: PatchField<UUID> = PatchField.Unset,
    )

    private fun updateAccessWithPatchFkResolve(executor: FakeQueryExecutor) =
        object : JooqUpdateAccess<String, UpdatePlaysetWithPatchGame, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val updateMapper =
                UpdateMapper<UpdatePlaysetWithPatchGame, UpdateValues> { UpdateValues() }

            override val idColumn = PLAYSET.ID
            override val versionColumn: TableField<*, *>? = null
            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val updateFkResolves =
                listOf(
                    GroupFkResolve<UpdatePlaysetWithPatchGame>(
                        fkColumn = PLAYSET.GAME_ID,
                        identityColumn = PLAYSET_GAME.PUBLIC_ID,
                        targetColumn = PLAYSET_GAME.ID,
                        valueSupplier = { (it.gameId as? PatchField.Value)?.value },
                        skip = { it.gameId is PatchField.Unset },
                    ),
                )
        }

    /**
     * A cascade-*owned* 1:1 group (`@PersistenceJoin(owned = true)`) - unlike
     * [CreatePlaysetWithGame] (a public id resolved against an *existing* [PLAYSET_GAME] row),
     * [game] here is a nested create command of its own: a fresh [PLAYSET_GAME] row is inserted
     * first, and *its* newly-generated [PLAYSET_GAME.ID] becomes [PLAYSET.GAME_ID]'s own value -
     * see [OwnedChildCreateWrite].
     */
    private data class CreateGameFixture(
        val publicId: UUID,
    )

    private data class CreatePlaysetWithOwnedGame(
        val name: String,
        val game: CreateGameFixture?,
    )

    private fun createAccessWithOwnedChild(executor: FakeQueryExecutor) =
        object : JooqCreateAccess<CreatePlaysetWithOwnedGame, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val createMapper =
                CreateMapper<CreatePlaysetWithOwnedGame, CreateValues> { cmd ->
                    CreateValues(PLAYSET.NAME to cmd.name)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

            override val createOwnedChildWrites =
                listOf(
                    OwnedChildCreateWrite<CreatePlaysetWithOwnedGame, CreateGameFixture>(
                        childCommand = { it.game },
                        childMapper = { game ->
                            CreateValues(PLAYSET_GAME.PUBLIC_ID to game.publicId)
                        },
                        childTable = PLAYSET_GAME,
                        childIdColumn = PLAYSET_GAME.ID,
                        parentFkColumn = PLAYSET.GAME_ID,
                    ),
                )
        }

    @Test
    fun `create with an owned child inserts the child's own row first, then folds its newly-generated id into the parent's own INSERT`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    returningRows =
                        listOf(
                            MapRowContext(mapOf("id" to 42L)),
                            MapRowContext(mapOf("id" to "new-id")),
                        ),
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                )
            val access = createAccessWithOwnedChild(executor)

            val publicGameId = UUID.randomUUID()
            access.create(
                CreatePlaysetWithOwnedGame(
                    name = "Wingspan",
                    game = CreateGameFixture(publicId = publicGameId),
                ),
            )

            // 1 child (PLAYSET_GAME) INSERT + 1 root (PLAYSET) INSERT + 1
            // re-select
            // SELECT.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val childInsert = executor.allQueries[0]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.sql.contains("game", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(publicGameId)).isTrue()
            val rootInsert = executor.allQueries[1]
            assertThat(rootInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(rootInsert.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(rootInsert.bindValues.contains(42L)).isTrue()
        }
    }

    @Test
    fun `create with an owned child but no incoming child command issues only the root insert and the re-select, leaving the FK column unset`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
            val access = createAccessWithOwnedChild(executor)

            access.create(CreatePlaysetWithOwnedGame(name = "Wingspan", game = null))

            // 1 root INSERT (no child INSERT at all) + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val rootInsert = executor.allQueries[0]
            assertThat(rootInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(rootInsert.sql.contains("game_id", ignoreCase = true)).isFalse()
        }
    }

    /**
     * An owned child with a 1:1 link of its own (e.g. `MatchEditPlaysetChange.newPlaysetId`):
     * [PLAYSET_NOTE] stands in for the child row, its [PLAYSET_NOTE.GAME_ID] resolved from a public
     * [PLAYSET_GAME] id carried by the child command.
     */
    private data class CreateLinkedNoteFixture(
        val text: String,
        val gameId: UUID,
    )

    private data class CreatePlaysetWithLinkedNote(
        val name: String,
        val note: CreateLinkedNoteFixture?,
    )

    private fun createAccessWithLinkedOwnedChild(executor: FakeQueryExecutor) =
        object : JooqCreateAccess<CreatePlaysetWithLinkedNote, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val createMapper =
                CreateMapper<CreatePlaysetWithLinkedNote, CreateValues> { cmd ->
                    CreateValues(PLAYSET.NAME to cmd.name)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

            override val createOwnedChildWrites =
                listOf(
                    OwnedChildCreateWrite<CreatePlaysetWithLinkedNote, CreateLinkedNoteFixture>(
                        childCommand = { it.note },
                        childMapper = { note -> CreateValues(PLAYSET_NOTE.TEXT to note.text) },
                        childTable = PLAYSET_NOTE,
                        childIdColumn = PLAYSET_NOTE.ID,
                        parentFkColumn = PLAYSET.GAME_ID,
                        childFkResolves =
                            listOf(
                                GroupFkResolve(
                                    fkColumn = PLAYSET_NOTE.GAME_ID,
                                    identityColumn = PLAYSET_GAME.PUBLIC_ID,
                                    targetColumn = PLAYSET_GAME.ID,
                                    valueSupplier = { it.gameId },
                                ),
                            ),
                    ),
                )
        }

    @Test
    fun `create with an owned child resolves the child's own 1-1 link inline in the child's own INSERT`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    returningRows =
                        listOf(
                            MapRowContext(mapOf("id" to 7L)),
                            MapRowContext(mapOf("id" to "new-id")),
                        ),
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                )
            val access = createAccessWithLinkedOwnedChild(executor)

            val publicGameId = UUID.randomUUID()
            access.create(
                CreatePlaysetWithLinkedNote(
                    name = "Wingspan",
                    note = CreateLinkedNoteFixture(text = "base game", gameId = publicGameId),
                ),
            )

            // 1 child INSERT (with the FK lookup as a derived table) + 1 root
            // INSERT + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val childInsert = executor.allQueries[0]
            assertThat(childInsert.sql.lowercase()).startsWith("insert into \"playset_note\"")
            assertThat(childInsert.sql).containsIgnoringCase("game_id")
            assertThat(childInsert.sql)
                .contains("from \"game\" where \"game\".\"public_id\" = cast(? as uuid)")
            assertThat(childInsert.bindValues).contains(publicGameId, "base game")
            val rootInsert = executor.allQueries[1]
            assertThat(rootInsert.bindValues).contains(7L)
        }
    }

    @Test
    fun `create with an owned child throws ReferencedEntityNotFoundException when the child's own link finds no row`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("new-id", "Wingspan", 0L)),
                    // The child INSERT collapses to zero rows: no PLAYSET_GAME match.
                    returningRows = listOf(null),
                    existsResult = { false },
                )
            val access = createAccessWithLinkedOwnedChild(executor)

            assertThatThrownBy {
                runBlocking {
                    access.create(
                        CreatePlaysetWithLinkedNote(
                            name = "Wingspan",
                            note =
                                CreateLinkedNoteFixture(
                                    text = "base game",
                                    gameId = UUID.randomUUID(),
                                ),
                        ),
                    )
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
        }
    }

    /**
     * An owned 1:1 group nested inside a managed 1:N group's own child element (e.g.
     * `MatchParticipant.loadout: MatchLoadout`, nested inside
     * `Match.participants`) - [PLAYSET_NOTE.GAME_ID] doubles as this nested owned FK column here,
     * the same way it already doubles as [OneToManyCreateWrite.elementFkResolves]'s own nested
     * *reference*-by-id FK column elsewhere in this file - `game` here is a nested create command
     * instead of a public id, cascading a fresh [PLAYSET_GAME] row per note.
     */
    private data class CreatePlaysetNoteWithOwnedGame(
        val id: UUID,
        val text: String,
        val game: CreateGameFixture?,
    )

    private data class CreatePlaysetWithOwnedGameNotes(
        val name: String,
        val notes: List<CreatePlaysetNoteWithOwnedGame> = emptyList(),
    )

    private fun createAccessWithNestedOwnedChild(executor: FakeQueryExecutor) =
        object : JooqCreateAccess<CreatePlaysetWithOwnedGameNotes, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val createMapper =
                CreateMapper<CreatePlaysetWithOwnedGameNotes, CreateValues> { cmd ->
                    CreateValues(PLAYSET.NAME to cmd.name)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

            override val createOneToManyWrites =
                listOf(
                    OneToManyCreateWrite(
                        childTable = PLAYSET_NOTE,
                        parentColumn = PLAYSET_NOTE.PLAYSET_ID,
                        parentRootColumn = PLAYSET.ID,
                        elements = { cmd: CreatePlaysetWithOwnedGameNotes -> cmd.notes },
                        elementMapper = { note ->
                            CreateValues(PLAYSET_NOTE.ID to note.id, PLAYSET_NOTE.TEXT to note.text)
                        },
                        elementOwnedChildWrites =
                            listOf(
                                OwnedChildCreateWrite(
                                    childCommand = { it.game },
                                    childMapper =
                                        CreateMapper { game ->
                                            CreateValues(PLAYSET_GAME.PUBLIC_ID to game.publicId)
                                        },
                                    childTable = PLAYSET_GAME,
                                    childIdColumn = PLAYSET_GAME.ID,
                                    parentFkColumn = PLAYSET_NOTE.GAME_ID,
                                ),
                            ),
                    ),
                )
        }

    @Test
    fun `create with an owned child nested inside a managed group's own element inserts that child's row before the element's own batch INSERT, folding its newly-generated id into that same row`() {
        runBlocking {
            val rootRow = playsetRow("new-id", "Wingspan", 0L)
            val gameRow = MapRowContext(mapOf("id" to 42L))
            val executor =
                FakeQueryExecutor(
                    rows = listOf(rootRow),
                    returningRows = listOf(rootRow, gameRow),
                    updateRowCounts = listOf(1),
                )
            val access = createAccessWithNestedOwnedChild(executor)
            val publicGameId = UUID.randomUUID()
            val note =
                CreatePlaysetNoteWithOwnedGame(
                    id = UUID.randomUUID(),
                    text = "first note",
                    game = CreateGameFixture(publicId = publicGameId),
                )

            access.create(CreatePlaysetWithOwnedGameNotes(name = "Wingspan", notes = listOf(note)))

            // 1 root (PLAYSET) INSERT + 1 owned-child (PLAYSET_GAME) INSERT + 1
            // note batch INSERT + 1 re-select SELECT.
            assertThat(executor.allQueries.size).isEqualTo(4)
            val rootInsert = executor.allQueries[0]
            assertThat(rootInsert.sql.lowercase().startsWith("insert")).isTrue()
            val childInsert = executor.allQueries[1]
            assertThat(childInsert.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(childInsert.sql.contains("game", ignoreCase = true)).isTrue()
            assertThat(childInsert.bindValues.contains(publicGameId)).isTrue()
            val noteInsert = executor.allQueries[2]
            assertThat(noteInsert.sql.contains("playset_note", ignoreCase = true)).isTrue()
            assertThat(noteInsert.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(noteInsert.bindValues.contains(42L)).isTrue()
            assertThat(noteInsert.bindValues.contains(note.text)).isTrue()
        }
    }

    private val twoFkResolvesForCreate =
        listOf(
            GroupFkResolve(
                fkColumn = PLAYSET.GAME_ID,
                identityColumn = PLAYSET_GAME.PUBLIC_ID,
                targetColumn = PLAYSET_GAME.ID,
                valueSupplier = { it.gameId },
            ),
            GroupFkResolve<CreatePlaysetWithGameAndOwner>(
                fkColumn = PLAYSET.OWNER_USER_ID,
                identityColumn = APP_USER.PUBLIC_ID,
                targetColumn = APP_USER.ID,
                valueSupplier = { it.ownerId },
            ),
        )

    private fun createAccessWithTwoFkResolves(executor: FakeQueryExecutor) =
        object : JooqCreateAccess<CreatePlaysetWithGameAndOwner, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val createMapper =
                CreateMapper<CreatePlaysetWithGameAndOwner, CreateValues> { cmd ->
                    CreateValues(PLAYSET.NAME to cmd.name)
                }

            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }

            override val createFkResolves = twoFkResolvesForCreate
        }

    private val twoFkResolvesForUpdate =
        listOf(
            GroupFkResolve(
                fkColumn = PLAYSET.GAME_ID,
                identityColumn = PLAYSET_GAME.PUBLIC_ID,
                targetColumn = PLAYSET_GAME.ID,
                valueSupplier = { it.gameId },
            ),
            GroupFkResolve<UpdatePlaysetWithGameAndOwner>(
                fkColumn = PLAYSET.OWNER_USER_ID,
                identityColumn = APP_USER.PUBLIC_ID,
                targetColumn = APP_USER.ID,
                valueSupplier = { it.ownerId },
            ),
        )

    private fun updateAccessWithTwoFkResolves(executor: FakeQueryExecutor) =
        object : JooqUpdateAccess<String, UpdatePlaysetWithGameAndOwner, PlaysetEntity> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val rowMapper = this@JooqAccessTest.rowMapper
            override val queryExecutor = executor
            override val updateMapper =
                UpdateMapper<UpdatePlaysetWithGameAndOwner, UpdateValues> { UpdateValues() }

            override val idColumn = PLAYSET.ID
            override val versionColumn: TableField<*, *>? = null
            override val fieldMapping = mapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@JooqAccessTest.idField
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val updateFkResolves = twoFkResolvesForUpdate
        }

    @Test
    fun `create resolves a group's identity-field FK inline, in the same INSERT statement`() {
        runBlocking {
            val publicGameId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(
                                mapOf("id" to "new-id", "name" to "Wingspan", "version" to 0L),
                            ),
                        ),
                )
            val access = createAccessWithFkResolve(executor)

            access.create(CreatePlaysetWithGame(name = "Wingspan", gameId = publicGameId))

            // 1 root INSERT (with the FK lookup nested inside it) + 1
            // re-select SELECT - no separate resolve round trip.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val insertQuery = executor.allQueries[0]
            assertThat(insertQuery.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(insertQuery.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(insertQuery.sql.contains("public_id", ignoreCase = true)).isTrue()
            assertThat(insertQuery.bindValues.contains(publicGameId)).isTrue()
        }
    }

    @Test
    fun `create throws ReferencedEntityNotFoundException when the FK lookup finds no row`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = emptyList(), returningRow = null)
            val access = createAccessWithFkResolve(executor)

            assertThatThrownBy {
                runBlocking {
                    access.create(
                        CreatePlaysetWithGame(name = "Wingspan", gameId = UUID.randomUUID()),
                    )
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
            // the INSERT itself (matched zero rows, since the FK lookup's
            // derived table was empty) + the fallback diagnostic exists()
            // check.
            assertThat(executor.allQueries.size).isEqualTo(2)
            assertThat(
                executor.allQueries[0]
                    .sql
                    .lowercase()
                    .startsWith("insert"),
            ).isTrue()
            val diagnosticQuery = executor.allQueries[1]
            assertThat(diagnosticQuery.sql.lowercase().startsWith("select")).isTrue()
            assertThat(diagnosticQuery.sql.contains("game", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `create with a null valueSupplier result for an optional group binds the FK column to null directly, no derived-table lookup`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(
                                mapOf("id" to "new-id", "name" to "Wingspan", "version" to 0L),
                            ),
                        ),
                )
            val access = createAccessWithNullableFkResolve(executor)

            access.create(CreatePlaysetWithNullableGame(name = "Wingspan", gameId = null))

            // No derived-table lookup at all - a plain INSERT ... RETURNING,
            // same as no FK resolves being declared - plus the re-select.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val insertQuery = executor.allQueries[0]
            assertThat(insertQuery.sql.lowercase().startsWith("insert")).isTrue()
            assertThat(insertQuery.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(insertQuery.sql.contains("public_id", ignoreCase = true)).isFalse()
        }
    }

    @Test
    fun `create attributes the ReferencedEntityNotFoundException to the right group when an entity has two FK resolves`() {
        runBlocking {
            val missingOwnerId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = emptyList(),
                    returningRow = null,
                    existsResult = { query -> query.sql.contains("game", ignoreCase = true) },
                )
            val access = createAccessWithTwoFkResolves(executor)

            val exception =
                catchThrowableOfType(ReferencedEntityNotFoundException::class.java) {
                    runBlocking {
                        access.create(
                            CreatePlaysetWithGameAndOwner(
                                name = "Wingspan",
                                gameId = UUID.randomUUID(),
                                ownerId = missingOwnerId,
                            ),
                        )
                    }
                }

            assertThat(exception.message!!.contains("app_user")).isTrue()
            assertThat(exception.message!!.contains("public_id")).isTrue()
        }
    }

    @Test
    fun `update resolves a group's identity-field FK inline, in the same UPDATE statement`() {
        runBlocking {
            val publicGameId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(mapOf("id" to "abc", "name" to "Catan", "version" to 2L)),
                        ),
                )
            val access = updateAccessWithFkResolve(executor)

            access.update(
                "abc",
                UpdatePlaysetWithGame(gameId = publicGameId),
                expectedVersion = null,
            )

            // 1 root UPDATE (with the FK lookup nested inside it) + 1
            // re-select SELECT - no separate resolve round trip.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val updateQuery = executor.allQueries[0]
            assertThat(updateQuery.sql.lowercase().startsWith("update")).isTrue()
            assertThat(updateQuery.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(updateQuery.sql.contains("public_id", ignoreCase = true)).isTrue()
            assertThat(updateQuery.bindValues.contains(publicGameId)).isTrue()
        }
    }

    @Test
    fun `update throws ReferencedEntityNotFoundException when the FK lookup finds no row`() {
        runBlocking {
            val executor = FakeQueryExecutor(rows = emptyList(), returningRow = null)
            val access = updateAccessWithFkResolve(executor)

            assertThatThrownBy {
                runBlocking {
                    access.update(
                        "abc",
                        UpdatePlaysetWithGame(gameId = UUID.randomUUID()),
                        expectedVersion = null,
                    )
                }
            }.isInstanceOf(ReferencedEntityNotFoundException::class.java)
            // the UPDATE itself (matched zero rows, since the FK lookup's
            // derived table was empty) + the fallback diagnostic exists()
            // check.
            assertThat(executor.allQueries.size).isEqualTo(2)
            assertThat(
                executor.allQueries[0]
                    .sql
                    .lowercase()
                    .startsWith("update"),
            ).isTrue()
            val diagnosticQuery = executor.allQueries[1]
            assertThat(diagnosticQuery.sql.lowercase().startsWith("select")).isTrue()
            assertThat(diagnosticQuery.sql.contains("game", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `update with a null valueSupplier result for an optional group binds the FK column to null directly, no derived-table lookup`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(mapOf("id" to "abc", "name" to "Catan", "version" to 2L)),
                        ),
                )
            val access = updateAccessWithNullableFkResolve(executor)

            access.update(
                "abc",
                UpdatePlaysetWithNullableGame(gameId = null),
                expectedVersion = null,
            )

            // No derived-table lookup at all - a plain UPDATE ... RETURNING,
            // same as no FK resolves being declared - plus the re-select.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val updateQuery = executor.allQueries[0]
            assertThat(updateQuery.sql.lowercase().startsWith("update")).isTrue()
            assertThat(updateQuery.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(updateQuery.sql.contains("public_id", ignoreCase = true)).isFalse()
        }
    }

    @Test
    fun `update with a skipped PatchField Unset group leaves the FK column out of the SET clause entirely, unlike a null valueSupplier result`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(mapOf("id" to "abc", "name" to "Catan", "version" to 2L)),
                        ),
                )
            val access = updateAccessWithPatchFkResolve(executor)

            access.update(
                "abc",
                UpdatePlaysetWithPatchGame(gameId = PatchField.Unset),
                expectedVersion = null,
            )

            // Distinct from the null-valueSupplier case above: the column
            // itself is absent from the SET clause, not set to NULL.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val updateQuery = executor.allQueries[0]
            assertThat(updateQuery.sql.lowercase().startsWith("update")).isTrue()
            assertThat(updateQuery.sql.contains("game_id", ignoreCase = true)).isFalse()
            assertThat(updateQuery.sql.contains("public_id", ignoreCase = true)).isFalse()
        }
    }

    @Test
    fun `update with a PatchField Value group resolves the FK via the usual derived-table lookup`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows =
                        listOf(
                            MapRowContext(mapOf("id" to "abc", "name" to "Catan", "version" to 2L)),
                        ),
                )
            val access = updateAccessWithPatchFkResolve(executor)
            val gameId = UUID.randomUUID()

            access.update(
                "abc",
                UpdatePlaysetWithPatchGame(gameId = PatchField.Value(gameId)),
                expectedVersion = null,
            )

            assertThat(executor.allQueries.size).isEqualTo(2)
            val updateQuery = executor.allQueries[0]
            assertThat(updateQuery.sql.lowercase().startsWith("update")).isTrue()
            assertThat(updateQuery.sql.contains("game_id", ignoreCase = true)).isTrue()
            assertThat(updateQuery.sql.contains("public_id", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `update attributes the ReferencedEntityNotFoundException to the right group when an entity has two FK resolves`() {
        runBlocking {
            val missingOwnerId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = emptyList(),
                    returningRow = null,
                    existsResult = { query -> query.sql.contains("game", ignoreCase = true) },
                )
            val access = updateAccessWithTwoFkResolves(executor)

            val exception =
                catchThrowableOfType(ReferencedEntityNotFoundException::class.java) {
                    runBlocking {
                        access.update(
                            "abc",
                            UpdatePlaysetWithGameAndOwner(
                                gameId = UUID.randomUUID(),
                                ownerId = missingOwnerId,
                            ),
                            expectedVersion = null,
                        )
                    }
                }

            assertThat(exception.message!!.contains("app_user")).isTrue()
            assertThat(exception.message!!.contains("public_id")).isTrue()
        }
    }

    @Test
    fun `updateAll batches same-shaped commands into one UPDATE and one bulk re-select on the happy path`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    allRowsSequence =
                        listOf(
                            listOf(playsetRow("a", "unused", 2L), playsetRow("b", "unused", 2L)),
                            listOf(
                                playsetRow("b", "Carcassonne v2", 2L),
                                playsetRow("a", "Catan v2", 2L),
                            ),
                        ),
                )
            val access = updateAccess(executor)

            val result =
                access.updateAll(
                    listOf(
                        UpdateBatchItem(
                            "a",
                            UpdatePlayset(name = "Catan v2"),
                            expectedVersion = 1L,
                        ),
                        UpdateBatchItem(
                            "b",
                            UpdatePlayset(name = "Carcassonne v2"),
                            expectedVersion = 1L,
                        ),
                    ),
                )

            assertThat(result.successes.map { it.name })
                .isEqualTo(listOf("Catan v2", "Carcassonne v2"))
            assertThat(result.failures.isEmpty()).isTrue()
            // Both commands share one "shape" (same single patched field, no
            // FK resolves) - 1 batch UPDATE ... RETURNING + 1 bulk re-select,
            // never one UPDATE per item.
            assertThat(executor.allQueries.size).isEqualTo(2)
            val updateSql =
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
            assertThat(updateSql.startsWith("update")).isTrue()
            assertThat(updateSql.contains("+ 1")).isTrue()
        }
    }

    @Test
    fun `updateAll only falls back to a single update for the id missing from the batch RETURNING, never redoing a match`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    allRowsSequence =
                        listOf(
                            // The batch UPDATE ... RETURNING only matched
                            // "a" - "b"'s own expectedVersion didn't match.
                            listOf(playsetRow("a", "unused", 2L)),
                            // The bulk re-select, for "a" only.
                            listOf(playsetRow("a", "Catan v2", 2L)),
                        ),
                    // "b"'s own fallback update()'s diagnostic exists()
                    // check - the row exists, so it's a stale version, not a
                    // missing id.
                    existsResult = { true },
                )
            val access = updateAccess(executor)

            val result =
                access.updateAll(
                    listOf(
                        UpdateBatchItem(
                            "a",
                            UpdatePlayset(name = "Catan v2"),
                            expectedVersion = 1L,
                        ),
                        UpdateBatchItem(
                            "b",
                            UpdatePlayset(name = "Carcassonne v2"),
                            expectedVersion = 1L,
                        ),
                    ),
                )

            assertThat(result.items.size).isEqualTo(2)
            val first = result.items[0]
            assertThat(first is BatchItemResult.Success).isTrue()
            assertThat((first as BatchItemResult.Success).value.name).isEqualTo("Catan v2")
            val second = result.items[1]
            assertThat(second is BatchItemResult.Failure).isTrue()
            assertThat((second as BatchItemResult.Failure).error is OptimisticLockException)
                .isTrue()
            // 1 batch UPDATE ... RETURNING + 1 bulk re-select (only "a") + 1
            // fallback UPDATE for "b" + 1 diagnostic exists() for "b" - "a"
            // is never redone.
            assertThat(executor.allQueries.size).isEqualTo(4)
        }
    }

    private fun deleteAccess(
        executor: FakeQueryExecutor,
        withVersionColumn: Boolean = true,
        withSoftDeleteColumn: Boolean = false,
    ) = object : JooqDeleteAccess<String> {

        override val dsl = this@JooqAccessTest.dsl
        override val table = PLAYSET
        override val queryExecutor = executor
        override val transactionalExecutor = FakeTransactionalExecutor(executor)
        override val idColumn = PLAYSET.ID
        override val versionColumn = if (withVersionColumn) PLAYSET.VERSION else null
        override val softDeleteColumn = if (withSoftDeleteColumn) PLAYSET.DELETED_AT else null
    }

    @Test
    fun `delete succeeds when a row was affected`() {
        runBlocking {
            val access = deleteAccess(FakeQueryExecutor(updateRowCount = 1))

            access.delete("abc", expectedVersion = 1L)
        }
    }

    @Test
    fun `delete throws OptimisticLockException when the row exists but expectedVersion is stale`() {
        runBlocking {
            val executor = FakeQueryExecutor(updateRowCount = 0, existsResult = { true })
            val access = deleteAccess(executor)

            assertThatThrownBy { runBlocking { access.delete("abc", expectedVersion = 99L) } }
                .isInstanceOf(OptimisticLockException::class.java)
            // the failed DELETE + the diagnostic exists() check.
            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `delete throws EntityNotFoundException when the row doesn't exist, even with a version column configured`() {
        runBlocking {
            val executor = FakeQueryExecutor(updateRowCount = 0)
            val access = deleteAccess(executor)

            assertThatThrownBy { runBlocking { access.delete("abc", expectedVersion = 99L) } }
                .isInstanceOf(EntityNotFoundException::class.java)
            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `delete throws EntityNotFoundException with no extra query when there's no version column`() {
        runBlocking {
            val executor = FakeQueryExecutor(updateRowCount = 0)
            val access = deleteAccess(executor, withVersionColumn = false)

            assertThatThrownBy { runBlocking { access.delete("abc", expectedVersion = null) } }
                .isInstanceOf(EntityNotFoundException::class.java)
            assertThat(executor.allQueries.size).isEqualTo(1)
        }
    }

    @Test
    fun `delete with a soft-delete column issues an UPDATE, not a DELETE`() {
        runBlocking {
            val executor = FakeQueryExecutor(updateRowCount = 1)
            val access = deleteAccess(executor, withSoftDeleteColumn = true)

            access.delete("abc", expectedVersion = 1L)

            val sql =
                executor.lastQuery
                    ?.sql
                    .orEmpty()
                    .lowercase()
            assertThat(sql.startsWith("update")).isTrue()
            assertThat(sql.contains("deleted_at")).isTrue()
            assertThat(sql.contains("+ 1")).isTrue() // version still atomically incremented
        }
    }

    @Test
    fun `delete without a soft-delete column issues a real DELETE`() {
        runBlocking {
            val executor = FakeQueryExecutor(updateRowCount = 1)
            val access = deleteAccess(executor, withSoftDeleteColumn = false)

            access.delete("abc", expectedVersion = 1L)

            val sql =
                executor.lastQuery
                    ?.sql
                    .orEmpty()
                    .lowercase()
            assertThat(sql.startsWith("delete")).isTrue()
        }
    }

    @Test
    fun `soft-delete throws OptimisticLockException when the row exists but expectedVersion is stale`() {
        runBlocking {
            val access =
                deleteAccess(
                    FakeQueryExecutor(updateRowCount = 0, existsResult = { true }),
                    withSoftDeleteColumn = true,
                )

            assertThatThrownBy { runBlocking { access.delete("abc", expectedVersion = 99L) } }
                .isInstanceOf(OptimisticLockException::class.java)
        }
    }

    @Test
    fun `soft-delete throws EntityNotFoundException when the row doesn't exist`() {
        runBlocking {
            val access =
                deleteAccess(FakeQueryExecutor(updateRowCount = 0), withSoftDeleteColumn = true)

            assertThatThrownBy { runBlocking { access.delete("abc", expectedVersion = 99L) } }
                .isInstanceOf(EntityNotFoundException::class.java)
        }
    }

    private fun deleteAccessWithOwnedChild(executor: FakeQueryExecutor) =
        object : JooqDeleteAccess<String> {

            override val dsl = this@JooqAccessTest.dsl
            override val table = PLAYSET
            override val queryExecutor = executor
            override val transactionalExecutor = FakeTransactionalExecutor(executor)
            override val idColumn = PLAYSET.ID
            override val versionColumn = PLAYSET.VERSION
            override val deleteOwnedChildWrites =
                listOf(
                    OwnedChildDeleteWrite(
                        childTable = PLAYSET_GAME,
                        childIdColumn = PLAYSET_GAME.ID,
                        parentFkColumn = PLAYSET.GAME_ID,
                    ),
                )
        }

    @Test
    fun `delete with an owned child reads the parent's own FK value first, deletes the parent, then deletes the owned child's own row`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(MapRowContext(mapOf("game_id" to 42L))),
                    updateRowCount = 1,
                )
            val access = deleteAccessWithOwnedChild(executor)

            access.delete("abc", expectedVersion = 1L)

            // 1 SELECT (read the FK value) + 1 parent DELETE + 1 child
            // DELETE, all through the same executor.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val selectQuery = executor.allQueries[0]
            assertThat(selectQuery.sql.lowercase().startsWith("select")).isTrue()
            assertThat(selectQuery.sql.contains("game_id", ignoreCase = true)).isTrue()
            val parentDelete = executor.allQueries[1]
            assertThat(parentDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(parentDelete.sql.contains("playset", ignoreCase = true)).isTrue()
            val childDelete = executor.allQueries[2]
            assertThat(childDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(childDelete.sql.contains("game", ignoreCase = true)).isTrue()
            assertThat(childDelete.bindValues.contains(42L)).isTrue()
        }
    }

    @Test
    fun `delete with an owned child but no FK value on the parent's own row skips the child delete entirely`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(MapRowContext(mapOf("game_id" to null))),
                    updateRowCount = 1,
                )
            val access = deleteAccessWithOwnedChild(executor)

            access.delete("abc", expectedVersion = 1L)

            // 1 SELECT + 1 parent DELETE only - no child delete issued.
            assertThat(executor.allQueries.size).isEqualTo(2)
        }
    }

    @Test
    fun `delete with an owned child still throws OptimisticLockException when the parent delete affects nothing, without deleting the child`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(MapRowContext(mapOf("game_id" to 42L))),
                    updateRowCount = 0,
                )
            val access = deleteAccessWithOwnedChild(executor)

            assertThatThrownBy { runBlocking { access.delete("abc", expectedVersion = 1L) } }
                .isInstanceOf(OptimisticLockException::class.java)

            // 1 SELECT (owned-child id pre-read) + 1 parent DELETE (affected
            // 0 rows) + 1 diagnostic exists() check (rows is non-empty, so it
            // confirms the row exists - a stale version, not "not found") -
            // no child delete, since the parent's own row turned out not to
            // match.
            assertThat(executor.allQueries.size).isEqualTo(3)
        }
    }

    private fun deleteAccessWithJunction(
        executor: FakeQueryExecutor,
        cascadeTarget: JunctionCascadeTarget? = null,
    ) = object : JooqDeleteAccess<String> {

        override val dsl = this@JooqAccessTest.dsl
        override val table = PLAYSET
        override val queryExecutor = executor
        override val transactionalExecutor = FakeTransactionalExecutor(executor)
        override val idColumn = PLAYSET.ID
        override val versionColumn = PLAYSET.VERSION
        override val deleteJunctionWrites =
            listOf(
                JunctionDeleteWrite(
                    table = PLAYSET_EXPANSION,
                    rootColumn = PLAYSET_EXPANSION.PLAYSET_ID,
                    parentRootColumn = PLAYSET.ID,
                    cascadeTarget = cascadeTarget,
                ),
            )
    }

    @Test
    fun `delete with a junction group removes only the link rows, never the target table - the real reported bug`() {
        runBlocking {
            // A real reported bug: the generated JunctionDeleteWrite used to
            // carry the TARGET table/column instead of the junction's own,
            // so this actually ran "delete from EXPANSION where EXPANSION.ID
            // = <playset id>" - corrupting shared catalog data. This is the
            // regression test for the fix.
            val playsetId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(MapRowContext(mapOf("id" to playsetId))),
                    updateRowCount = 1,
                )
            val access = deleteAccessWithJunction(executor)

            access.delete("abc", expectedVersion = 1L)

            // 1 SELECT (read the parent root value) + 1 junction DELETE + 1
            // PLAYSET's own DELETE - never a query against the target
            // (EXPANSION) table.
            assertThat(executor.allQueries.size).isEqualTo(3)
            val junctionDelete = executor.allQueries[1]
            assertThat(junctionDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(junctionDelete.sql.contains("playset_expansion", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `delete with an owned junction group also cascade-deletes the now-unlinked target rows`() {
        runBlocking {
            val playsetId = UUID.randomUUID()
            val expansionId1 = 10L
            val expansionId2 = 20L
            val executor =
                FakeQueryExecutor(
                    rows = listOf(MapRowContext(mapOf("id" to playsetId))),
                    resolveRows =
                        listOf(
                            MapRowContext(mapOf("expansion_id" to expansionId1)),
                            MapRowContext(mapOf("expansion_id" to expansionId2)),
                        ),
                    updateRowCount = 1,
                )
            val access =
                deleteAccessWithJunction(
                    executor,
                    cascadeTarget =
                        JunctionCascadeTarget(
                            table = EXPANSION,
                            targetColumn = EXPANSION.ID,
                            junctionTargetColumn = PLAYSET_EXPANSION.EXPANSION_ID,
                        ),
                )

            access.delete("abc", expectedVersion = 1L)

            // 1 SELECT (parent root value) + 1 SELECT (target ids about to
            // be unlinked) + 1 junction DELETE + 1 target DELETE + 1
            // PLAYSET's own DELETE.
            assertThat(executor.allQueries.size).isEqualTo(5)
            val targetIdSelect = executor.allQueries[1]
            assertThat(targetIdSelect.sql.lowercase().startsWith("select")).isTrue()
            val junctionDelete = executor.allQueries[2]
            assertThat(junctionDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(junctionDelete.sql.contains("playset_expansion", ignoreCase = true)).isTrue()
            val targetDelete = executor.allQueries[3]
            assertThat(targetDelete.sql.lowercase().startsWith("delete")).isTrue()
            assertThat(targetDelete.sql.contains("expansion", ignoreCase = true)).isTrue()
            assertThat(targetDelete.bindValues.contains(expansionId1)).isTrue()
            assertThat(targetDelete.bindValues.contains(expansionId2)).isTrue()
        }
    }

    @Test
    fun `delete with an owned junction group skips the target delete entirely when nothing was linked`() {
        runBlocking {
            val playsetId = UUID.randomUUID()
            val executor =
                FakeQueryExecutor(
                    rows = listOf(MapRowContext(mapOf("id" to playsetId))),
                    resolveRows = emptyList(),
                    updateRowCount = 1,
                )
            val access =
                deleteAccessWithJunction(
                    executor,
                    cascadeTarget =
                        JunctionCascadeTarget(
                            table = EXPANSION,
                            targetColumn = EXPANSION.ID,
                            junctionTargetColumn = PLAYSET_EXPANSION.EXPANSION_ID,
                        ),
                )

            access.delete("abc", expectedVersion = 1L)

            // 1 SELECT (parent root value) + 1 SELECT (target ids, empty) +
            // 1 junction DELETE + 1 PLAYSET's own DELETE - no target DELETE
            // issued.
            assertThat(executor.allQueries.size).isEqualTo(4)
        }
    }

    @Test
    fun `deleteAll batches every id into one DELETE with RETURNING`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("a", "unused", 1L), playsetRow("b", "unused", 1L)),
                )
            val access = deleteAccess(executor)

            val result =
                access.deleteAll(
                    listOf(
                        DeleteBatchItem("a", expectedVersion = 1L),
                        DeleteBatchItem("b", expectedVersion = 1L),
                    ),
                )

            assertThat(result.items.count { it is BatchItemResult.Success }).isEqualTo(2)
            // deleteOwnedChildWrites/deleteOneToManyWrites/deleteJunctionWrites
            // are all empty by default, so there's nothing to bulk-read
            // beforehand - just the one batched DELETE ... RETURNING, never
            // one DELETE per id.
            assertThat(executor.allQueries.size).isEqualTo(1)
            assertThat(
                executor.allQueries
                    .first()
                    .sql
                    .lowercase()
                    .startsWith("delete"),
            ).isTrue()
        }
    }

    @Test
    fun `deleteAll only falls back to a single delete for the id missing from the batch RETURNING, never redoing a match`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    // Only "a" comes back from the batch DELETE ...
                    // RETURNING - "b"'s own expectedVersion didn't match.
                    rows = listOf(playsetRow("a", "unused", 1L)),
                    // "b"'s own fallback delete()'s plain DELETE affects
                    // nothing...
                    updateRowCount = 0,
                    // ...and its diagnostic exists() check finds the row, so
                    // it's a stale version, not a missing id.
                    existsResult = { true },
                )
            val access = deleteAccess(executor)

            val result =
                access.deleteAll(
                    listOf(
                        DeleteBatchItem("a", expectedVersion = 1L),
                        DeleteBatchItem("b", expectedVersion = 1L),
                    ),
                )

            assertThat(result.items.size).isEqualTo(2)
            assertThat(result.items[0] is BatchItemResult.Success).isTrue()
            val second = result.items[1]
            assertThat(second is BatchItemResult.Failure).isTrue()
            assertThat((second as BatchItemResult.Failure).error is OptimisticLockException)
                .isTrue()
            // 1 batch DELETE ... RETURNING + 1 fallback DELETE for "b" + 1
            // diagnostic exists() for "b" - "a" is never redone.
            assertThat(executor.allQueries.size).isEqualTo(3)
        }
    }

    @Test
    fun `delete runs its cascades and the root delete in a single transaction`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("abc", "unused", 1L)),
                    updateRowCount = 1,
                )
            val transactional = FakeTransactionalExecutor(executor)
            val access =
                object : JooqDeleteAccess<String> {

                    override val dsl = this@JooqAccessTest.dsl
                    override val table = PLAYSET
                    override val queryExecutor = FakeQueryExecutor()
                    override val transactionalExecutor = transactional
                    override val idColumn = PLAYSET.ID
                    override val versionColumn = PLAYSET.VERSION
                    override val deleteJunctionWrites =
                        listOf(
                            JunctionDeleteWrite(
                                table = PLAYSET_EXPANSION,
                                rootColumn = PLAYSET_EXPANSION.PLAYSET_ID,
                                parentRootColumn = PLAYSET.ID,
                            ),
                        )
                }

            access.delete("abc", expectedVersion = 1L)

            assertThat(transactional.transactionCount).isEqualTo(1)
            // The cascade read, the junction delete and the root delete all went through the
            // transaction's own executor - none through the (non-transactional) queryExecutor.
            assertThat(executor.allQueries.map { it.sql.lowercase().substringBefore(' ') })
                .containsExactly("select", "delete", "delete")
            assertThat((access.queryExecutor as FakeQueryExecutor).allQueries).isEmpty()
        }
    }

    @Test
    fun `deleteAll runs the batched delete in a single transaction`() {
        runBlocking {
            val executor =
                FakeQueryExecutor(
                    rows = listOf(playsetRow("a", "unused", 1L), playsetRow("b", "unused", 1L)),
                )
            val transactional = FakeTransactionalExecutor(executor)
            val access =
                object : JooqDeleteAccess<String> {

                    override val dsl = this@JooqAccessTest.dsl
                    override val table = PLAYSET
                    override val queryExecutor = FakeQueryExecutor()
                    override val transactionalExecutor = transactional
                    override val idColumn = PLAYSET.ID
                    override val versionColumn = PLAYSET.VERSION
                }

            access.deleteAll(
                listOf(
                    DeleteBatchItem("a", expectedVersion = 1L),
                    DeleteBatchItem("b", expectedVersion = 1L),
                ),
            )

            assertThat(transactional.transactionCount).isEqualTo(1)
        }
    }

    @Test
    fun `createAll lets a cancellation propagate instead of recording it as a failed item`() {
        val executor = FakeQueryExecutor(rows = listOf(playsetRow("new-id", "Wingspan", 0L)))
        val access =
            object : JooqCreateAccess<CreatePlayset, PlaysetEntity> {

                override val dsl = this@JooqAccessTest.dsl
                override val table = PLAYSET
                override val rowMapper = this@JooqAccessTest.rowMapper
                override val queryExecutor = executor
                override val createMapper =
                    CreateMapper<CreatePlayset, CreateValues> {
                        throw CancellationException("request cancelled")
                    }

                override val fieldMapping = mapping
                override val queryTranslator = JooqSearchQueryTranslator()
                override val idField = this@JooqAccessTest.idField
                override val transactionalExecutor = FakeTransactionalExecutor(executor)
                override val extractRootId: (RowContext) -> Any = { row -> row.string("id") }
            }

        assertThatThrownBy {
            runBlocking { access.createAll(listOf(CreatePlayset(name = "Wingspan"))) }
        }.isInstanceOf(CancellationException::class.java)
    }
}
