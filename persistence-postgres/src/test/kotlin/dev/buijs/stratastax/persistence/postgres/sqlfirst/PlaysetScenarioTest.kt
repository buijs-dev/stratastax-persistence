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
package dev.buijs.stratastax.persistence.postgres.sqlfirst

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.entity.core.api.PatchField
import dev.buijs.stratastax.entity.core.api.asc
import dev.buijs.stratastax.persistence.core.api.BatchItemResult
import dev.buijs.stratastax.persistence.core.api.BatchResult
import dev.buijs.stratastax.persistence.core.api.CreateAccess
import dev.buijs.stratastax.persistence.core.api.DeleteAccess
import dev.buijs.stratastax.persistence.core.api.DeleteBatchItem
import dev.buijs.stratastax.persistence.core.api.ReadAccess
import dev.buijs.stratastax.persistence.core.api.UnitOfWork
import dev.buijs.stratastax.persistence.core.api.UpdateAccess
import dev.buijs.stratastax.persistence.core.api.UpdateBatchItem
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreateLoadout
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreateNote
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreatePlayset
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreateReaction
import dev.buijs.stratastax.persistence.postgres.testfixtures.Database
import dev.buijs.stratastax.persistence.postgres.testfixtures.PlaysetEntity
import dev.buijs.stratastax.persistence.postgres.testfixtures.PlaysetModel
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.after
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.before
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.eq
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.isIn
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.isOut
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.search
import dev.buijs.stratastax.persistence.postgres.testfixtures.UpdatePlayset
import dev.buijs.stratastax.persistence.test.api.QueryExecutionRecorder
import dev.buijs.stratastax.persistence.test.api.UPDATE_SNAPSHOTS_PROPERTY
import dev.buijs.stratastax.search.core.api.LikeExpression
import dev.buijs.stratastax.search.core.api.Page
import dev.buijs.stratastax.search.core.spi.CursorElement
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * What the playset ([PlaysetSqlStore]) does in every scenario, on a clean database each: the results
 * (the timestamps a write sets aside), the rows it leaves behind and how many statements it runs -
 * compared with the snapshot recorded under `src/test/resources/scenarios`. The snapshots were
 * recorded when this route still ran side by side with the jOOQ-built one, which gave the same
 * results and rows.
 *
 * A missing snapshot is recorded on first run (on CI it fails instead); with the system property
 * [UPDATE_SNAPSHOTS_PROPERTY] set to `true` every snapshot is re-recorded.
 */
internal class PlaysetScenarioTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = Database.start()

        private val SNAPSHOTS = Path.of("src/test/resources/scenarios")
    }

    @Test
    fun `create with every kind of relation and read it back`() =
        assertScenario("create with every relation, findById") { playsets ->
            val created = playsets.create.create(full(1))
            listOf(created, playsets.read.findById(created.id), playsets.read.findById(id(999)))
        }

    @Test
    fun `createAll reports a missing reference on its own and creates the other commands`() =
        assertScenario("createAll with a missing reference") { playsets ->
            val created =
                playsets.create.createAll(
                    listOf(
                        plain(1, "a"),
                        plain(2, "b").copy(gameId = MISSING),
                        plain(3, "c").copy(gameId = CATAN),
                    ),
                )
            listOf(created, playsets.read.findAll(search()))
        }

    @Test
    fun `create with an unknown expansion fails and stores nothing`() =
        assertScenario("create with an unknown expansion") { playsets ->
            listOf(
                attempt {
                    playsets.create.create(
                        plain(1, "a").copy(expansionIds = setOf(SEAFARERS, MISSING)),
                    )
                },
                playsets.read.count(search()),
            )
        }

    @Test
    fun `filters on root, joined, one-to-many, junction and nested fields`() =
        assertScenario("filters on every kind of field") { playsets ->
            playsets.create.create(full(1))
            playsets.create.create(
                plain(2, "board").copy(gameId = AZUL, expansionIds = setOf(CITIES)),
            )
            playsets.create.create(plain(3, "card"))
            val notesGameName = EntityField("notesGameName", EntityFieldType.STRING)
            val filters =
                listOf(
                    eq(PlaysetModel.nameField, "board"),
                    eq(PlaysetModel.gameNameField, null),
                    isIn(PlaysetModel.gameNameField, "Catan", null),
                    isOut(PlaysetModel.gameNameField, "Catan", "Azul"),
                    isOut(PlaysetModel.gameNameField, "Catan", null),
                    eq(PlaysetModel.notesTextField, "first"),
                    eq(PlaysetModel.expansionsNameField, "Cities"),
                    isIn(PlaysetModel.expansionsNameField, "Cities", "Seafarers"),
                    eq(PlaysetModel.reactionsEmojiField, "b"),
                    eq(notesGameName, "Azul"),
                    LikeExpression(PlaysetModel.nameField, "B%", ignoreCase = true),
                    LikeExpression(PlaysetModel.nameField, "B%"),
                )
            filters.map { playsets.read.findAll(search(it)) } +
                filters.map { playsets.read.count(search(it)) }
        }

    @Test
    fun `sorts on a joined field and pages with a keyset cursor in both directions`() =
        assertScenario("sort on a relation, keyset paging") { playsets ->
            listOf(
                "a" to CATAN,
                "b" to null,
                "c" to AZUL,
                "d" to null,
                "e" to CATAN,
            ).forEachIndexed {
                index,
                (name, game),
                ->
                playsets.create.create(plain(index + 1, name).copy(gameId = game))
            }

            val byName = listOf(PlaysetModel.nameField.asc(), PlaysetModel.idField.asc())
            val byGame = listOf(PlaysetModel.gameNameField.asc(), PlaysetModel.idField.asc())
            val first = playsets.read.findAll(search(sort = byGame, limit = 2))
            val last = first.items.last()
            listOf(
                playsets.read.findAll(search(sort = byName, limit = 2)),
                playsets.read.findAll(
                    search(
                        sort = byName,
                        limit = 2,
                        cursor = after(text("b"), text(id(2).toString())),
                    ),
                ),
                playsets.read.findAll(
                    search(
                        sort = byName,
                        limit = 2,
                        cursor = before(text("d"), text(id(4).toString())),
                    ),
                ),
                first,
                playsets.read.findAll(
                    search(
                        sort = byGame,
                        limit = 2,
                        cursor = after(text(last.gameName), text(last.id.toString())),
                    ),
                ),
                playsets.read.findAll(
                    search(
                        sort = byGame,
                        limit = 2,
                        cursor = after(text(null), text(id(2).toString())),
                    ),
                ),
                playsets.read.findAll(
                    search(
                        sort = byGame,
                        limit = 2,
                        cursor = before(text(null), text(id(4).toString())),
                    ),
                ),
            )
        }

    @Test
    fun `update patches fields, replaces links and synchronizes notes with their reactions`() =
        assertScenario("update fields, links and notes") { playsets ->
            playsets.create.create(full(1))
            val updated =
                playsets.update.update(
                    id(1),
                    UpdatePlayset(
                        name = PatchField.Value("renamed"),
                        gameId = PatchField.Value(AZUL),
                        attributesJson = PatchField.Value(null),
                        expansionIds = PatchField.Value(setOf(CITIES, TRADERS)),
                        notes =
                            PatchField.Value(
                                listOf(
                                    CreateNote(
                                        "first, edited",
                                        id = id(11),
                                        reactions =
                                            listOf(
                                                CreateReaction("z", id = id(21)),
                                                CreateReaction("c", id = id(23)),
                                            ),
                                    ),
                                    CreateNote(
                                        "third",
                                        id = id(13),
                                        gameId = CATAN,
                                        loadout = CreateLoadout("note loadout"),
                                    ),
                                ),
                            ),
                    ),
                    "1",
                )
            listOf(updated, playsets.read.findById(id(1)))
        }

    @Test
    fun `update leaves what it does not mention, and unlinks everything for an empty value`() =
        assertScenario("update partially, unlink all") { playsets ->
            playsets.create.create(full(1))
            listOf(
                playsets.update.update(id(1), UpdatePlayset(), 1L),
                playsets.update.update(
                    id(1),
                    UpdatePlayset(
                        expansionIds = PatchField.Value(null),
                        gameId = PatchField.Value(null),
                    ),
                    2L,
                ),
            )
        }

    @Test
    fun `update fails for a stale version, an unknown id, an unknown reference and a missing version`() =
        assertScenario("update failures") { playsets ->
            playsets.create.create(full(1))
            val rename = UpdatePlayset(name = PatchField.Value("renamed"))
            listOf(
                attempt { playsets.update.update(id(1), rename, 5L) },
                attempt { playsets.update.update(id(999), rename, 1L) },
                attempt {
                    playsets.update.update(
                        id(1),
                        UpdatePlayset(gameId = PatchField.Value(MISSING)),
                        1L,
                    )
                },
                attempt {
                    playsets.update.update(
                        id(1),
                        UpdatePlayset(expansionIds = PatchField.Value(setOf(MISSING))),
                        1L,
                    )
                },
                attempt {
                    playsets.update.update(
                        id(1),
                        UpdatePlayset(
                            notes = PatchField.Value(listOf(CreateNote("x", gameId = MISSING))),
                        ),
                        1L,
                    )
                },
                attempt { playsets.update.update(id(1), rename, null) },
                attempt { playsets.update.update(id(1), rename, "not a version") },
                playsets.read.findById(id(1)),
            )
        }

    @Test
    fun `updateAll reports a stale, an unknown and a duplicate item on their own`() =
        assertScenario("updateAll with misses") { playsets ->
            playsets.create.createAll(listOf(plain(1, "a"), plain(2, "b")))
            val rename = { name: String -> UpdatePlayset(name = PatchField.Value(name)) }

            listOf(
                playsets.update.updateAll(
                    listOf(
                        UpdateBatchItem(id(1), rename("a2"), 1L),
                        UpdateBatchItem(id(2), rename("b2"), 9L),
                        UpdateBatchItem(id(1), rename("a3"), 1L),
                        UpdateBatchItem(id(999), rename("x"), 1L),
                    ),
                ),
                playsets.read.findAll(search()),
            )
        }

    @Test
    fun `updateAll falls back to one update per item when the batch fails as a whole`() =
        assertScenario("updateAll fallback") { playsets ->
            playsets.create.createAll(listOf(plain(1, "a"), plain(2, "b")))
            listOf(
                playsets.update.updateAll(
                    listOf(
                        UpdateBatchItem(id(1), UpdatePlayset(name = PatchField.Value("a2")), 1L),
                        UpdateBatchItem(
                            id(2),
                            UpdatePlayset(gameId = PatchField.Value(MISSING)),
                            1L,
                        ),
                    ),
                ),
                playsets.read.findAll(search()),
            )
        }

    @Test
    fun `a removed note is soft-deleted and restored when it is sent again`() =
        assertScenario("notes removed and restored") { playsets ->
            playsets.create.create(full(1))
            val first = CreateNote("first", id = id(11))
            val second = CreateNote("second", id = id(12))
            listOf(
                playsets.update.update(
                    id(1),
                    UpdatePlayset(notes = PatchField.Value(listOf(first))),
                    1L,
                ),
                playsets.update.update(
                    id(1),
                    UpdatePlayset(notes = PatchField.Value(listOf(first, second))),
                    2L,
                ),
                playsets.update.update(
                    id(1),
                    UpdatePlayset(notes = PatchField.Value(emptyList())),
                    3L,
                ),
            )
        }

    @Test
    fun `with REPLACE_ALL every note is soft-deleted first and the ones sent again restored`() =
        assertScenario("notes replaced", replaceNotes = true) { playsets ->
            playsets.create.create(full(1))
            listOf(
                playsets.update.update(
                    id(1),
                    UpdatePlayset(
                        notes =
                            PatchField.Value(
                                listOf(
                                    CreateNote("first, again", id = id(11)),
                                    CreateNote("new", id = id(14)),
                                ),
                            ),
                    ),
                    1L,
                ),
            )
        }

    @Test
    fun `delete soft-deletes the playset and its notes, and reports stale, unknown and repeated deletes`() =
        assertScenario("delete and deleteAll") { playsets ->
            playsets.create.createAll(listOf(full(1), plain(2, "b"), plain(3, "c")))
            listOf(
                attempt { playsets.delete.delete(id(1), 7L) },
                attempt { playsets.delete.delete(id(1), "1") },
                attempt { playsets.delete.delete(id(1), 2L) },
                playsets.delete.deleteAll(
                    listOf(
                        DeleteBatchItem(id(2), 1L),
                        DeleteBatchItem(id(999), 1L),
                        DeleteBatchItem(id(2), 1L),
                        DeleteBatchItem(id(3), null),
                    ),
                ),
                playsets.read.findAll(search()),
                playsets.read.count(search()),
            )
        }

    @Test
    fun `writes in one unit of work commit together and roll back together`() =
        assertScenario("unit of work") { playsets ->
            playsets.unitOfWork.transaction {
                playsets.create.create(plain(1, "a"))
                playsets.create.create(plain(2, "b"))
            }

            listOf(
                attempt {
                    playsets.unitOfWork.transaction {
                        playsets.create.create(plain(3, "c"))
                        error("rolled back")
                    }
                },
                playsets.unitOfWork.transaction {
                    attempt { playsets.create.create(plain(4, "d").copy(ownerId = MISSING)) }
                },
                playsets.read.findAll(search()),
            )
        }

    /** The four access interfaces of the playset, and the unit of work they run in. */
    private class Playsets(
        val read: ReadAccess<UUID, PlaysetEntity>,
        val create: CreateAccess<CreatePlayset, PlaysetEntity>,
        val update: UpdateAccess<UUID, UpdatePlayset, PlaysetEntity>,
        val delete: DeleteAccess<UUID>,
        val unitOfWork: UnitOfWork,
    )

    private class Run(
        val outcome: List<Any?>,
        val rows: Map<String, List<Map<String, Any?>>>,
        val statements: Int,
    )

    /** Runs [scenario] on a clean database and compares what it did with its snapshot. */
    private fun assertScenario(
        name: String,
        replaceNotes: Boolean = false,
        scenario: suspend (Playsets) -> List<Any?>,
    ) {
        // given
        val playsets = { listener: QueryExecutionListener ->
            PlaysetSqlStore(replaceNotes = replaceNotes, listeners = listOf(listener)).run {
                Playsets(read, create, update, delete, transactional)
            }
        }

        // when
        val actual = render(run(playsets, scenario))

        // then
        val file = SNAPSHOTS.resolve(name.replace(' ', '-').replace(",", "") + ".txt")
        val update = System.getProperty(UPDATE_SNAPSHOTS_PROPERTY).toBoolean()
        if (update || !file.exists()) {
            check(
                update || !System.getenv("CI").toBoolean(),
            ) { "No scenario snapshot recorded at $file" }

            SNAPSHOTS.createDirectories()
            file.writeText(actual)
        }

        assertThat(actual).isEqualTo(file.readText())
    }

    /** [run] as text: the statements it ran, its outcome and the rows it left, one per line. */
    private fun render(run: Run): String =
        buildString {
            appendLine("statements: ${run.statements}")
            appendLine()
            appendLine("outcome:")
            run.outcome.forEach { appendLine("  $it") }

            run.rows.forEach { (table, rows) ->
                appendLine()
                appendLine("$table:")
                rows.forEach { appendLine("  $it") }
            }
        }

    private fun run(
        playsets: (QueryExecutionListener) -> Playsets,
        scenario: suspend (Playsets) -> List<Any?>,
    ): Run {
        Database.truncate()
        seed()
        val recorder = QueryExecutionRecorder()
        val outcome = runBlocking { scenario(playsets(recorder)) }.map(::normalized)
        val statements = recorder.executions().count { !it.operation.isTransactionControl }

        return Run(outcome, snapshot(), statements)
    }

    /** The outcome of [block]: its value, or the class and message of what it threw. */
    private suspend fun attempt(block: suspend () -> Any?): Any? =
        runCatchingNonCancellation { block() }.fold({ it }, ::describe)

    private fun normalized(value: Any?): Any? =
        when (value) {
            is PlaysetEntity -> value.copy(createdAt = EPOCH)
            is Page<*> -> Page(value.items.map(::normalized), value.hasMore)
            is BatchResult<*> -> value.items.map(::normalized)
            is BatchItemResult.Success<*> -> "success" to normalized(value.value)
            is BatchItemResult.Failure -> "failure" to describe(value.error)
            is List<*> -> value.map(::normalized)
            else -> value
        }

    private fun describe(error: Throwable): String = "${error::class.simpleName}: ${error.message}"

    /** Every row the scenarios write, by public id rather than generated key, timestamps as set or not. */
    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        mapOf(
            "playset" to
                """
                SELECT p.public_id, p.name, p.attributes::text AS attributes, p.version, p.deleted_at IS NOT NULL AS deleted,
                       g.public_id AS game, u.public_id AS owner, l.label AS loadout
                FROM playset p
                LEFT JOIN game g ON p.game_id = g.id
                JOIN app_user u ON p.owner_id = u.id
                LEFT JOIN loadout l ON p.loadout_id = l.id
                ORDER BY p.public_id
                """,
            "playset_note" to
                """
                SELECT n.public_id, p.public_id AS playset, n.text, g.public_id AS game, l.label AS loadout, n.version,
                       n.deleted_at IS NOT NULL AS deleted
                FROM playset_note n
                JOIN playset p ON n.playset_id = p.id
                LEFT JOIN game g ON n.game_id = g.id
                LEFT JOIN loadout l ON n.loadout_id = l.id
                ORDER BY n.public_id
                """,
            "note_reaction" to
                "SELECT public_id, note_id, emoji FROM note_reaction ORDER BY public_id",
            "playset_expansion" to
                """
                SELECT p.public_id AS playset, e.public_id AS expansion
                FROM playset_expansion x
                JOIN playset p ON x.playset_id = p.id
                JOIN expansion e ON x.expansion_id = e.id
                ORDER BY 1, 2
                """,
            "loadout" to
                "SELECT label, deleted_at IS NOT NULL AS deleted FROM loadout ORDER BY label, deleted",
        ).mapValues { (_, sql) ->
            Database.query(sql.trimIndent()).map { row -> row.mapValues { it.value?.toString() } }
        }
}

private val EPOCH = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

private fun id(number: Int): UUID = UUID(0, number.toLong())

private val ADA = UUID(1, 1)
private val CATAN = UUID(2, 1)
private val AZUL = UUID(2, 2)
private val SEAFARERS = UUID(3, 1)
private val CITIES = UUID(3, 2)
private val TRADERS = UUID(3, 3)
private val MISSING = UUID(9, 9)

/** The reference data every scenario starts with, under fixed ids so both routes see the same. */
private fun seed() {
    Database.jdbc(
        "INSERT INTO app_user (public_id, display_name) VALUES ('$ADA', 'Ada')",
        "INSERT INTO game (public_id, name) VALUES ('$CATAN', 'Catan'), ('$AZUL', 'Azul')",
        "INSERT INTO expansion (public_id, name) VALUES ('$SEAFARERS', 'Seafarers'), ('$CITIES', 'Cities'), ('$TRADERS', 'Traders')",
    )
}

private fun plain(
    number: Int,
    name: String,
) = CreatePlayset(name = name, ownerId = ADA, id = id(number))

/** A playset with every kind of relation: a game, attributes, a loadout, expansions and notes with reactions. */
private fun full(number: Int) =
    CreatePlayset(
        name = "Friday $number",
        ownerId = ADA,
        id = id(number),
        gameId = CATAN,
        attributesJson = """{"players": 4, "tags": ["family"]}""",
        expansionIds = setOf(SEAFARERS, CITIES),
        loadout = CreateLoadout("standard $number"),
        notes =
            listOf(
                CreateNote(
                    "first",
                    id = id(number * 10 + 1),
                    gameId = AZUL,
                    reactions =
                        listOf(
                            CreateReaction("a", id = id(number * 20 + 1)),
                            CreateReaction(
                                "b",
                                id =
                                    id(number * 20 + 2),
                            ),
                        ),
                    loadout = CreateLoadout("note $number"),
                ),
                CreateNote("second", id = id(number * 10 + 2)),
            ),
    )

private fun text(value: String?): CursorElement =
    value?.let { CursorElement.StringValue(it) } ?: CursorElement.NullValue
