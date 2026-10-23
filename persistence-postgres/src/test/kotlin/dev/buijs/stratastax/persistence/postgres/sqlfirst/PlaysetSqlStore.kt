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

import dev.buijs.stratastax.entity.core.api.orNullIfUnset
import dev.buijs.stratastax.persistence.core.api.BatchResult
import dev.buijs.stratastax.persistence.core.api.CreateAccess
import dev.buijs.stratastax.persistence.core.api.DeleteAccess
import dev.buijs.stratastax.persistence.core.api.DeleteBatchItem
import dev.buijs.stratastax.persistence.core.api.ReadAccess
import dev.buijs.stratastax.persistence.core.api.UpdateAccess
import dev.buijs.stratastax.persistence.core.api.UpdateBatchItem
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.postgres.api.PostgresDialect
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreateLoadout
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreateNote
import dev.buijs.stratastax.persistence.postgres.testfixtures.CreatePlayset
import dev.buijs.stratastax.persistence.postgres.testfixtures.Database
import dev.buijs.stratastax.persistence.postgres.testfixtures.PlaysetEntity
import dev.buijs.stratastax.persistence.postgres.testfixtures.PlaysetModel
import dev.buijs.stratastax.persistence.postgres.testfixtures.UpdatePlayset
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import dev.buijs.stratastax.persistence.reactive.spi.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.spi.SqlStatement
import dev.buijs.stratastax.persistence.reactive.spi.SqlVersion
import dev.buijs.stratastax.persistence.reactive.spi.TransactionalExecutor
import dev.buijs.stratastax.persistence.reactive.spi.batchOrEach
import dev.buijs.stratastax.persistence.reactive.spi.childDiff
import dev.buijs.stratastax.persistence.reactive.spi.countQuery
import dev.buijs.stratastax.persistence.reactive.spi.diagnoseMiss
import dev.buijs.stratastax.persistence.reactive.spi.inKeyOrder
import dev.buijs.stratastax.persistence.reactive.spi.listQuery
import dev.buijs.stratastax.persistence.reactive.spi.lockQuery
import dev.buijs.stratastax.persistence.reactive.spi.pageQuery
import dev.buijs.stratastax.persistence.reactive.spi.readAll
import dev.buijs.stratastax.persistence.reactive.spi.readPage
import dev.buijs.stratastax.persistence.reactive.spi.rejectedInputs
import dev.buijs.stratastax.persistence.reactive.spi.requireAllReferenced
import dev.buijs.stratastax.persistence.reactive.spi.requireReferences
import dev.buijs.stratastax.persistence.reactive.spi.requireWritten
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.Page
import dev.buijs.stratastax.search.core.api.SearchQuery
import java.util.UUID

/*
 * The access classes of the playset over the statements of PlaysetSql: what stratastax-codegen
 * generates, written by hand for this proof of concept. Every step is one statement, for all inputs
 * of a batch at once; the library only runs them, binds the parameters and explains a miss.
 */

private val VERSION = SqlVersion("version")

private const val TABLE = "playset"

/** The playset's four access classes over [Database.pool], wired as an application would. */
internal class PlaysetSqlStore(
    replaceNotes: Boolean = false,
    listeners: List<QueryExecutionListener> = emptyList(),
) {

    val transactional =
        VertxTransactionalExecutor(Database.pool, PostgresDialect, listeners = listeners)

    private val createMapper = PlaysetCreateMapper()

    private val writes = PlaysetWrites(PlaysetModel.rowMapper, createMapper)

    val read =
        PlaysetSqlReadAccess(
            transactional.ambientQueryExecutor(),
            transactional,
            PlaysetModel.rowMapper,
        )

    val create = PlaysetSqlCreateAccess(transactional, createMapper, writes)

    val update =
        PlaysetSqlUpdateAccess(
            transactional,
            createMapper,
            PlaysetUpdateMapper(),
            writes,
            replaceNotes,
        )

    val delete = PlaysetSqlDeleteAccess(transactional)
}

internal class PlaysetSqlReadAccess(
    private val queries: QueryExecutor,
    private val transactional: TransactionalExecutor,
    private val rowMapper: RowMapper<PlaysetEntity>,
) : ReadAccess<UUID, PlaysetEntity> {

    private val names = RowMapper { row -> row.string("name") }

    override suspend fun findById(id: UUID): PlaysetEntity? =
        queries.queryFirst(PlaysetSql.FIND_BY_ID, listOf(id))?.let(rowMapper::map)

    override suspend fun findAll(search: SearchQuery): Page<PlaysetEntity> =
        queries.readPage(PlaysetSearchSql.sql.pageQuery(search), search, rowMapper)

    /** [findAll], restricted to [scope] - what the caller may see - on top of the search's filter. */
    suspend fun findAll(
        search: SearchQuery,
        scope: FilterExpression,
    ): Page<PlaysetEntity> =
        queries.readPage(PlaysetSearchSql.sql.pageQuery(search, scope), search, rowMapper)

    override suspend fun count(search: SearchQuery): Long =
        count(PlaysetSearchSql.sql.countQuery(search))

    suspend fun count(
        search: SearchQuery,
        scope: FilterExpression,
    ): Long = count(PlaysetSearchSql.sql.countQuery(search, scope))

    /** One page of names: the `name` projection. */
    suspend fun findAllNames(search: SearchQuery): Page<String> =
        queries.readPage(
            PlaysetSearchSql.sql.pageQuery(search, select = PlaysetSql.SELECT_NAMES),
            search,
            names,
        )

    /** Every name matching [search], ignoring its limit and cursor. */
    suspend fun findAllNamesList(search: SearchQuery): List<String> =
        queries.readAll(
            PlaysetSearchSql.sql.listQuery(search, select = PlaysetSql.SELECT_NAMES),
            search,
            names,
        )

    /**
     * [findAll], locking the returned playsets until the surrounding transaction ends, skipping the
     * ones another transaction locked with [skipLocked].
     */
    suspend fun findAllForUpdate(
        search: SearchQuery,
        skipLocked: Boolean = true,
    ): List<PlaysetEntity> {
        check(transactional.isInTransaction()) {
            "findAllForUpdate must run inside a transaction (see UnitOfWork.transaction): " +
                "outside one the row locks are released as soon as the query completes"
        }

        return queries.readAll(
            PlaysetSearchSql.sql.lockQuery(search, skipLocked),
            search,
            rowMapper,
        )
    }

    private suspend fun count(statement: SqlStatement): Long =
        queries.queryFirst(statement.sql, statement.params)?.long("count") ?: 0L
}

/**
 * Creates playsets, one statement per step for every command at once: the owned loadouts, the
 * playsets (resolving game and owner), their expansion links, their notes (with loadouts) and the
 * notes' reactions; then reads them back.
 */
internal class PlaysetSqlCreateAccess(
    private val transactional: TransactionalExecutor,
    private val mapper: PlaysetCreateMapper,
    private val writes: PlaysetWrites,
) : CreateAccess<CreatePlayset, PlaysetEntity> {

    override suspend fun create(command: CreatePlayset): PlaysetEntity =
        transactional.withTransaction { sql -> insert(sql, listOf(command)).single() }

    override suspend fun createAll(commands: List<CreatePlayset>): BatchResult<PlaysetEntity> =
        batchOrEach(
            commands,
            all = { all ->
                transactional.withTransaction { sql -> insert(sql, all).map { Result.success(it) } }
            },
            each = { create(it) },
        )

    private suspend fun insert(
        sql: QueryExecutor,
        commands: List<CreatePlayset>,
    ): List<PlaysetEntity> {
        val loadoutIds = writes.insertLoadouts(sql, commands.map { it.loadout })
        val inserted =
            sql.batch(
                PlaysetSql.INSERT,
                commands.mapIndexed {
                    index,
                    command,
                    ->
                    mapper.insert(command, loadoutIds[index])
                },
            )
        val playsetIds =
            inserted.mapIndexed { index, rows ->
                sql.requireWritten(rows, TABLE, mapper.references(commands[index])).long("id")
            }

        val links =
            commands.indices.mapNotNull { index ->
                commands[index].expansionIds?.let {
                    playsetIds[index] to
                        it
                }
            }

        writes.requireExpansions(sql, links)
        writes.insertExpansions(sql, links)

        val notes =
            commands.indices.flatMap { index ->
                commands[index].notes.map {
                    it to
                        playsetIds[index]
                }
            }

        writes.insertNotes(sql, notes)
        writes.insertReactions(sql, notes.map { it.first })
        return writes.readBack(sql, commands.map { it.id })
    }
}

/**
 * Updates playsets: one patch statement for every item at once, then the expansion links and the
 * notes (matched by id, with their nested reactions) of the items that matched; then reads them
 * back. An item that matched nothing is explained by [diagnoseMiss].
 *
 * @param replaceNotes REPLACE_ALL instead of UPSERT for the notes - in generated code one or the
 *   other, as the annotation says.
 */
internal class PlaysetSqlUpdateAccess(
    private val transactional: TransactionalExecutor,
    private val createMapper: PlaysetCreateMapper,
    private val mapper: PlaysetUpdateMapper,
    private val writes: PlaysetWrites,
    private val replaceNotes: Boolean = false,
) : UpdateAccess<UUID, UpdatePlayset, PlaysetEntity> {

    override suspend fun update(
        id: UUID,
        command: UpdatePlayset,
        expectedVersion: Any?,
    ): PlaysetEntity =
        transactional.withTransaction { sql ->
            updateRows(
                sql,
                listOf(UpdateBatchItem(id, command, expectedVersion)),
            ).single().getOrThrow()
        }

    override suspend fun updateAll(
        items: List<UpdateBatchItem<UUID, UpdatePlayset>>,
    ): BatchResult<PlaysetEntity> =
        batchOrEach(
            items,
            all = { all -> transactional.withTransaction { sql -> updateRows(sql, all) } },
            each = { update(it.id, it.command, it.expectedVersion) },
            rejected = rejected(items),
        )

    private fun rejected(items: List<UpdateBatchItem<UUID, UpdatePlayset>>): List<Throwable?> =
        rejectedInputs(TABLE, items.map { it.id }, items.map { it.expectedVersion }, VERSION)

    private suspend fun updateRows(
        sql: QueryExecutor,
        items: List<UpdateBatchItem<UUID, UpdatePlayset>>,
    ): List<Result<PlaysetEntity>> {
        val rejected = rejected(items)
        val candidates = items.indices.filter { rejected[it] == null }

        val updated =
            sql.batch(
                PlaysetSql.UPDATE,
                candidates.map {
                    mapper.update(
                        items[it].id,
                        VERSION.valueOf(items[it].expectedVersion),
                        items[it].command,
                    )
                },
            )

        // A candidate that matched nothing fails the batch when it references a missing row.
        val playsetIds = HashMap<Int, Long>()
        candidates.forEachIndexed { position, index ->
            val row = updated[position].singleOrNull()
            if (row == null) {
                sql.requireReferences(mapper.references(items[index].command))
            } else {
                playsetIds[index] =
                    row.long("id")
            }
        }

        val matched = candidates.filter { it in playsetIds }

        val links =
            matched.mapNotNull { index ->
                mapper.expansions(items[index].command)?.let {
                    playsetIds.getValue(index) to
                        it
                }
            }

        writes.requireExpansions(sql, links)
        sql.batch(
            PlaysetSql.DELETE_EXPANSIONS_EXCEPT,
            links.map { (playsetId, keep) -> listOf(playsetId, keep.toTypedArray()) },
        )
        writes.insertExpansions(sql, links)

        synchronizeNotes(
            sql,
            matched.mapNotNull { index ->
                items[index].command.notes.orNullIfUnset()?.let {
                    playsetIds.getValue(index) to
                        it
                }
            },
        )

        val entities = matched.zip(writes.readBack(sql, matched.map { items[it].id })).toMap()
        return items.indices.map { index ->
            val item = items[index]
            rejected[index]?.let { Result.failure(it) }
                ?: entities[index]?.let { Result.success(it) }
                ?: Result.failure(
                    sql.diagnoseMiss(
                        TABLE,
                        PlaysetSql.ACTIVE_ROW,
                        item.id,
                        item.expectedVersion,
                        versioned = true,
                    ),
                )
        }
    }

    /**
     * Synchronizes the notes of the playsets ([parents]: playset id and its command's notes) with
     * the existing rows, soft-deleted ones included: a matching note is updated (and restored), a
     * new one inserted, and one no longer named soft-deleted - with REPLACE_ALL every active note is
     * soft-deleted first.
     */
    private suspend fun synchronizeNotes(
        sql: QueryExecutor,
        parents: List<Pair<Long, Collection<CreateNote>>>,
    ) {
        if (parents.isEmpty()) return
        val existing =
            sql.query(
                PlaysetSql.NOTE_KEYS,
                listOf(parents.map { it.first }.toTypedArray()),
            )
        val existingIds = existing.map { it.uuid("public_id") }.toSet()
        val activeIds =
            existing
                .filter {
                    it.boolean(
                        "active",
                    )
                }.map { it.uuid("public_id") }
                .toSet()
        val notes = parents.flatMap { (playsetId, notes) -> notes.map { it to playsetId } }

        if (replaceNotes && activeIds.isNotEmpty()) {
            sql.execute(PlaysetSql.SOFT_DELETE_NOTES, listOf(activeIds.toTypedArray()))
        }

        val diff =
            childDiff(
                notes,
                { it.first.id },
                existingIds,
                if (replaceNotes) emptySet() else activeIds,
            )
        val updated =
            sql.batch(
                PlaysetSql.UPDATE_NOTE,
                diff.update.map { (note, playsetId) -> mapper.updateNote(note, playsetId) },
            )
        updated.forEachIndexed { index, rows ->
            sql.requireWritten(
                rows,
                "playset_note",
                createMapper.noteReferences(diff.update[index].first),
            )
        }

        writes.insertNotes(sql, diff.insert)
        synchronizeReactions(sql, notes.map { it.first })
        if (diff.remove.isNotEmpty()) {
            sql.execute(
                PlaysetSql.SOFT_DELETE_NOTES,
                listOf(diff.remove.toTypedArray()),
            )
        }
    }

    /** Synchronizes the reactions of [notes] by id; a reaction no longer named is deleted. */
    private suspend fun synchronizeReactions(
        sql: QueryExecutor,
        notes: List<CreateNote>,
    ) {
        if (notes.isEmpty()) return
        val existingIds =
            sql
                .query(
                    PlaysetSql.REACTION_KEYS,
                    listOf(
                        notes
                            .map { it.id }
                            .toTypedArray(),
                    ),
                ).map { it.uuid("public_id") }
                .toSet()
        val reactions = notes.flatMap { note -> note.reactions.map { it to note.id } }

        val diff = childDiff(reactions, { it.first.id }, existingIds, existingIds)
        sql.batch(
            PlaysetSql.UPDATE_REACTION,
            diff.update.map { (reaction, noteId) -> mapper.updateReaction(reaction, noteId) },
        )
        sql.batch(
            PlaysetSql.INSERT_REACTION,
            diff.insert.map { (reaction, noteId) -> createMapper.insertReaction(reaction, noteId) },
        )
        if (diff.remove.isNotEmpty()) {
            sql.execute(
                PlaysetSql.DELETE_REACTIONS,
                listOf(diff.remove.toTypedArray()),
            )
        }
    }
}

/**
 * Soft-deletes playsets - checking and incrementing the version - and then their notes; the
 * expansion links, reactions and loadouts stay, so a playset can be restored.
 */
internal class PlaysetSqlDeleteAccess(
    private val transactional: TransactionalExecutor,
) : DeleteAccess<UUID> {

    override suspend fun delete(
        id: UUID,
        expectedVersion: Any?,
    ) {
        transactional.withTransaction { sql ->
            deleteRows(sql, listOf(DeleteBatchItem(id, expectedVersion))).single().getOrThrow()
        }
    }

    override suspend fun deleteAll(items: List<DeleteBatchItem<UUID>>): BatchResult<Unit> =
        batchOrEach(
            items,
            all = { all -> transactional.withTransaction { sql -> deleteRows(sql, all) } },
            each = { delete(it.id, it.expectedVersion) },
            rejected = rejected(items),
        )

    private fun rejected(items: List<DeleteBatchItem<UUID>>): List<Throwable?> =
        rejectedInputs(TABLE, items.map { it.id }, items.map { it.expectedVersion }, VERSION)

    private suspend fun deleteRows(
        sql: QueryExecutor,
        items: List<DeleteBatchItem<UUID>>,
    ): List<Result<Unit>> {
        val rejected = rejected(items)
        val candidates = items.indices.filter { rejected[it] == null }

        val deleted =
            sql.batch(
                PlaysetSql.SOFT_DELETE,
                candidates.map { listOf(items[it].id, VERSION.valueOf(items[it].expectedVersion)) },
            )

        val playsetIds = deleted.flatten().map { it.long("id") }

        if (playsetIds.isNotEmpty()) {
            sql.execute(
                PlaysetSql.SOFT_DELETE_NOTES_OF,
                listOf(playsetIds.toTypedArray()),
            )
        }

        val removed =
            candidates
                .filterIndexed { position, _ -> deleted[position].isNotEmpty() }
                .toSet()
        return items.indices.map { index ->
            val item = items[index]
            rejected[index]?.let { Result.failure(it) }
                ?: if (index in removed) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        sql.diagnoseMiss(
                            TABLE,
                            PlaysetSql.ACTIVE_ROW,
                            item.id,
                            item.expectedVersion,
                            versioned = true,
                        ),
                    )
                }
        }
    }
}

/** The write steps the create and the update access share, each one statement for every input at once. */
internal class PlaysetWrites(
    private val rowMapper: RowMapper<PlaysetEntity>,
    private val createMapper: PlaysetCreateMapper,
) {

    /** Inserts the loadouts there are and returns per input the new loadout's id, `null` for none. */
    suspend fun insertLoadouts(
        sql: QueryExecutor,
        loadouts: List<CreateLoadout?>,
    ): List<Long?> {
        val present = loadouts.mapIndexedNotNull { index, loadout -> loadout?.let { index to it } }

        val inserted =
            sql.batch(
                PlaysetSql.INSERT_LOADOUT,
                present.map { (_, loadout) -> listOf(loadout.label) },
            )
        val byIndex = present.map { it.first }.zip(inserted.map { it.single().long("id") }).toMap()
        return loadouts.indices.map { byIndex[it] }
    }

    /** Checks that every expansion the [links] (playset id and expansion public ids) name exists. */
    suspend fun requireExpansions(
        sql: QueryExecutor,
        links: List<Pair<Long, Set<UUID>>>,
    ) = sql.requireAllReferenced(
        "expansion",
        "public_id",
        PlaysetSql.EXISTING_EXPANSIONS,
        links.flatMap { it.second }.distinct().toTypedArray(),
    )

    /** Links each playset to the expansions it does not link yet. */
    suspend fun insertExpansions(
        sql: QueryExecutor,
        links: List<Pair<Long, Set<UUID>>>,
    ) {
        sql.batch(
            PlaysetSql.INSERT_EXPANSIONS,
            links.map { (playsetId, ids) -> listOf(playsetId, ids.toTypedArray()) },
        )
    }

    /** Inserts [notes] (each with its playset id) and their owned loadouts - not their reactions. */
    suspend fun insertNotes(
        sql: QueryExecutor,
        notes: List<Pair<CreateNote, Long>>,
    ) {
        val loadoutIds = insertLoadouts(sql, notes.map { it.first.loadout })
        val inserted =
            sql.batch(
                PlaysetSql.INSERT_NOTE,
                notes.mapIndexed {
                    index,
                    (note, playsetId),
                    ->
                    createMapper.insertNote(note, playsetId, loadoutIds[index])
                },
            )
        inserted.forEachIndexed { index, rows ->
            sql.requireWritten(
                rows,
                "playset_note",
                createMapper.noteReferences(notes[index].first),
            )
        }
    }

    /** Inserts the reactions of [notes]. */
    suspend fun insertReactions(
        sql: QueryExecutor,
        notes: List<CreateNote>,
    ) {
        sql.batch(
            PlaysetSql.INSERT_REACTION,
            notes.flatMap { note ->
                note.reactions.map { createMapper.insertReaction(it, note.id) }
            },
        )
    }

    /** Reads the written playsets back through the read projection, in the order of [ids]. */
    suspend fun readBack(
        sql: QueryExecutor,
        ids: List<UUID>,
    ): List<PlaysetEntity> {
        if (ids.isEmpty()) return emptyList()
        return sql
            .query(PlaysetSql.SELECT_BY_IDS, listOf(ids.toTypedArray()))
            .inKeyOrder(ids, "id", TABLE)
            .map(rowMapper::map)
    }
}
