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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.persistence.core.spi.BatchItemResult
import dev.buijs.stratastax.persistence.core.spi.BatchResult
import dev.buijs.stratastax.persistence.core.spi.CreateAccess
import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.reactive.app.CreateValues
import dev.buijs.stratastax.persistence.reactive.app.GroupFkResolve
import dev.buijs.stratastax.persistence.reactive.app.JunctionWrite
import dev.buijs.stratastax.persistence.reactive.app.OneToManyCreateWrite
import dev.buijs.stratastax.persistence.reactive.app.OwnedChildCreateWrite
import dev.buijs.stratastax.persistence.reactive.app.buildBatchInsertQuery
import dev.buijs.stratastax.persistence.reactive.app.buildBatchInsertReturningQuery
import dev.buijs.stratastax.persistence.reactive.app.buildCreateQuery
import dev.buijs.stratastax.persistence.reactive.app.junctionRootAlias
import dev.buijs.stratastax.persistence.reactive.app.resolveElementFkValuesBatch
import dev.buijs.stratastax.persistence.reactive.app.resolveJunctionTargetIds
import dev.buijs.stratastax.persistence.reactive.app.selectByIdParts
import dev.buijs.stratastax.persistence.reactive.app.selectByIdsParts
import dev.buijs.stratastax.persistence.reactive.app.throwOnMissingFkReference
import dev.buijs.stratastax.persistence.reactive.app.writeOwnedChildRow
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table

/**
 * Generic create-side mixin a generated `${Entity}CreateAccess` implements - an `INSERT ...
 * RETURNING` (used only to learn the new row's id and to detect a genuinely failed insert),
 * followed by a re-select through [queryTranslator]/ [fieldMapping] (the same
 * aliased/joined/MULTISET projection [rowMapper] already expects from `JooqReadAccess.findById` - a
 * bare `RETURNING *` row only has the root table's raw, unaliased columns, which is wrong for any
 * entity with a joined field or a 1:N group), both inside one [transactionalExecutor] transaction
 * so the write and the re-select see a consistent state.
 *
 * [createJunctionWrites] covers a many-to-many `junction<...> { create(...) }` relation (see
 * `JooqJunctionBuilder` in search-jooq) - e.g. `expansionIds` rows to insert into a
 * `PLAYSET_EXPANSION` link table alongside the new `PLAYSET` row, written inside the same
 * transaction as the insert and re-select. [extractRootId] reads the new row's id back out of the
 * `RETURNING` result (untyped at this generic level, since the id's real Kotlin type is only known
 * by whichever concrete `${Entity}CreateAccess` wires this property, e.g. `{ row ->
 * row.uuid(playsetSearch.id.name) }`) - used both as the junction rows' root-side value and as the
 * id to re-select by. `RETURNING` selects and aliases only [idField]'s own physical column, as
 * `idField.name` (see [buildCreateQuery]) - a bare `RETURNING *`'s labels are the table's raw
 * physical column names, which can differ from (and even collide with) `idField.name` when the id
 * field is bound to a differently named column (e.g. `field("id") { column = PLAYSET.PUBLIC_ID }`),
 * so `extractRootId`'s `idField.name`-keyed lookup would silently read the wrong column otherwise.
 *
 * Named `createJunctionWrites` rather than the shorter `junctionWrites` so a single generated
 * repository can implement both this and
 * [dev.buijs.stratastax.persistence.reactive.api.JooqUpdateAccess] - which needs its own,
 * differently-typed `List<JunctionWrite<U>>` - without a Kotlin property clash (`C` and `U` are
 * unrelated types, so one property name can't carry both).
 *
 * [createFkResolves] covers a 1:1 group whose identity field carries a `create(...)` binding (see
 * `JooqOneToOneGroupFieldBuilder.create` in search-jooq) - the command's value there is a public id
 * (e.g. `GAME.PUBLIC_ID`), not the physical FK column's own raw value (e.g. `PLAYSET.GAME_ID`, a
 * `Long` surrogate key), so it's resolved inline, as a joined derived table in the same `INSERT ...
 * SELECT ... FROM` statement as the write itself (see [buildCreateQuery]/[GroupFkResolve]) - no
 * separate round trip. A missing reference collapses the statement to zero inserted rows rather
 * than a DB error; [throwOnMissingFkReference] re-diagnoses that rare case afterward to produce a
 * precise [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException].
 */
interface JooqCreateAccess<C, T> : CreateAccess<C, T> {

    val dsl: DSLContext
    val table: Table<*>
    val rowMapper: RowMapper<T>
    val queryExecutor: QueryExecutor
    val createMapper: CreateMapper<C, CreateValues>
    val fieldMapping: JooqFieldMapping
    val queryTranslator: JooqSearchQueryTranslator
    val idField: EntityField
    val transactionalExecutor: TransactionalExecutor
    val extractRootId: (RowContext) -> Any
    val createJunctionWrites: List<JunctionWrite<C>>
        get() = emptyList()

    val createFkResolves: List<GroupFkResolve<C>>
        get() = emptyList()

    /**
     * Covers a top-level *managed* direct-FK `oneToManyGroup<...> { publicApi { create(...) } }`
     * relation (see [OneToManyCreateWrite]'s own kdoc) - e.g. `MATCH_PARTICIPANT` rows inserted
     * alongside the new `MATCH` row, in the same transaction as the insert and re-select.
     */
    val createOneToManyWrites: List<OneToManyCreateWrite<C, *>>
        get() = emptyList()

    /**
     * Covers a top-level *owned* 1:1 `@PersistenceJoin(owned = true)` relation (see
     * [OwnedChildCreateWrite]'s own kdoc) - e.g. a fresh `LOADOUT` row inserted *before* the new
     * parent row, whose own generated id becomes this parent's own FK column value.
     */
    val createOwnedChildWrites: List<OwnedChildCreateWrite<C, *>>
        get() = emptyList()

    override suspend fun create(command: C): T {
        return transactionalExecutor.withTransaction { txExecutor ->
            val ownedChildFkValues = writeOwnedChildRows(txExecutor, command)
            val setValues = createMapper.map(command).values + ownedChildFkValues
            val parentRootColumns =
                (
                    createJunctionWrites.map { it.parentRootColumn } +
                        createOneToManyWrites.map { it.parentRootColumn }
                ).distinct()

            val query =
                dsl.buildCreateQuery(
                    table,
                    setValues,
                    createFkResolves,
                    command,
                    fieldMapping.fields
                        .getValue(idField.name)
                        .field
                        .`as`(idField.name),
                    parentRootColumns.map { it.`as`(junctionRootAlias(it)) },
                )
            val insertedRow =
                txExecutor.executeReturning(query)
                    ?: run {
                        dsl.throwOnMissingFkReference(txExecutor, createFkResolves, command)
                        error(
                            "INSERT ... RETURNING produced no row - this should be impossible on a successful insert",
                        )
                    }

            val rootId = extractRootId(insertedRow)
            writeJunctionRows(txExecutor, command, insertedRow)
            writeOneToManyRows(txExecutor, command, insertedRow)

            val parts = queryTranslator.translateById(idField, rootId, fieldMapping)
            val row =
                txExecutor.executeAndGetFirst(dsl.selectByIdParts(table, parts))
                    ?: error("re-select after INSERT produced no row - this should be impossible")
            rowMapper.map(row)
        }
    }

    /**
     * Inserts every owned child's own row *before* the parent's own INSERT - the parent's own FK
     * column value (e.g. `MATCH_PARTICIPANT.LOADOUT_ID`) needs the child's newly-generated id at
     * INSERT time, unlike [writeJunctionRows]/[writeOneToManyRows] which both run *after* (they
     * need the parent's own newly-generated id instead). Returns the resolved FK column/value pairs
     * to fold into the parent's own [CreateValues] - `emptyMap()` when [createOwnedChildWrites] is
     * empty or every [OwnedChildCreateWrite.childCommand] returns `null` (no owned child to create
     * for this particular command).
     */
    private suspend fun writeOwnedChildRows(
        executor: QueryExecutor,
        command: C,
    ): Map<Field<*>, Any?> =
        createOwnedChildWrites
            .mapNotNull { write -> writeOwnedChildRow(dsl, executor, write, command) }
            .toMap()

    private suspend fun writeJunctionRows(
        executor: QueryExecutor,
        command: C,
        insertedRow: RowContext,
    ) {
        createJunctionWrites.forEach { junction ->
            val targetIds = junction.mapper.targetIds(command) ?: return@forEach
            val parentRootValue =
                insertedRow.rawOrNull(junctionRootAlias(junction.parentRootColumn))
                    ?: error(
                        "junction root column '${junction.parentRootColumn.name}' was " +
                            "not returned by INSERT ... RETURNING - this should be impossible",
                    )
            val resolvedTargetIds = resolveTargetIds(executor, junction, targetIds)
            if (resolvedTargetIds.isEmpty()) return@forEach
            val columns: List<Field<*>> = listOf(junction.rootColumn, junction.targetColumn)
            val rows = resolvedTargetIds.map { targetId -> listOf(parentRootValue, targetId) }

            executor.executeUpdate(dsl.buildBatchInsertQuery(junction.table, columns, rows))
        }
    }

    private suspend fun writeOneToManyRows(
        executor: QueryExecutor,
        command: C,
        insertedRow: RowContext,
    ) {
        createOneToManyWrites.forEach { write ->
            val parentRootValue =
                insertedRow.rawOrNull(junctionRootAlias(write.parentRootColumn))
                    ?: error(
                        "one-to-many parent root column '${write.parentRootColumn.name}' " +
                            "was not returned by INSERT ... RETURNING - this should be impossible",
                    )
            writeOneToManyElements(write, listOf(command to parentRootValue), executor)
        }
    }

    /**
     * Generic over both [C2] and [E] - **not** tied to this interface's own `C` - so this same
     * function can recurse into [OneToManyCreateWrite.elementWrites] (a managed group nested inside
     * *this* group's own child element, e.g. `MatchParticipant.achievements`), where the "command"
     * for that nested write is [E] (this level's own element type), not the root command. Calling
     * `write.elements`/`elementMapper` off a `OneToManyCreateWrite<C, *>` receiver directly (rather
     * than through one generic function) doesn't compile either way - Kotlin can't prove two
     * independent property accesses on a star-projected instance share one captured type.
     *
     * Takes a *list* of (command, parentRootValue) [parents] rather than one
     * - at the top level there's only ever one (create-side has exactly one root command per call),
     *   but a recursive call passes every sibling element at the current depth at once, so every
     *   element's own child rows - across *all* siblings - fold into one [buildBatchInsertQuery]
     *   statement instead of one INSERT per sibling. [resolveElementFkValuesBatch] does the same
     *   batching for a nested identity group's own per-element FK lookup - one `SELECT ... IN
     *   (...)` for every element at this depth, rather than one derived-table join per element's
     *   own INSERT.
     */
    private suspend fun <C2, E> writeOneToManyElements(
        write: OneToManyCreateWrite<C2, E>,
        parents: List<Pair<C2, Any?>>,
        executor: QueryExecutor,
    ) {
        data class Row(
            val element: E,
            val parentRootValue: Any?,
        )
        val rows =
            parents.flatMap { (command, parentRootValue) ->
                write.elements(command).map { Row(it, parentRootValue) }
            }

        if (rows.isEmpty()) return

        val mapped = rows.map { it to write.elementMapper.map(it.element) }

        val fkValuesPerRow =
            resolveElementFkValuesBatch(
                dsl,
                executor,
                write.elementFkResolves,
                rows.map { it.element },
            )
        // One at a time, per element - unlike fkValuesPerRow's single batched
        // SELECT, each owned child is a fresh row of its own, inserted before
        // this element's own row joins the batch INSERT below, so its
        // newly-generated id can be folded into that same row (see
        // OwnedChildCreateWrite's own kdoc).
        val ownedChildValuesPerRow: List<Map<Field<*>, Any?>> =
            mapped.map { (row, _) ->
                write.elementOwnedChildWrites
                    .mapNotNull { ownedWrite ->
                        writeOwnedChildRow(dsl, executor, ownedWrite, row.element)
                    }.toMap()
            }

        val columns: List<Field<*>> =
            mapped
                .first()
                .second.values.keys
                .toList() +
                write.parentColumn +
                write.elementFkResolves.map { it.fkColumn } +
                write.elementOwnedChildWrites.map { it.parentFkColumn }

        val valueRows: List<List<Any?>> =
            mapped.mapIndexed { index, (row, values) ->
                columns.map { column ->
                    when {
                        column == write.parentColumn -> {
                            row.parentRootValue
                        }
                        column in fkValuesPerRow[index] -> {
                            fkValuesPerRow[index].getValue(column)
                        }
                        column in ownedChildValuesPerRow[index] -> {
                            ownedChildValuesPerRow[index].getValue(column)
                        }
                        else -> {
                            values.values[column]
                        }
                    }
                }
            }

        val affected =
            executor.executeUpdate(dsl.buildBatchInsertQuery(write.childTable, columns, valueRows))
        check(affected == rows.size) {
            "one-to-many batch INSERT affected $affected rows, expected ${rows.size}"
        }

        if (write.elementWrites.isNotEmpty()) {
            val nestedParents: List<Pair<E, Any?>> =
                mapped.map { (row, values) ->
                    val childId =
                        requireNotNull(write.childIdentityValue(values)) {
                            "one-to-many child's own childIdentityColumn had no value in its " +
                                "own CreateValues - this should be impossible when elementWrites is configured"
                        }

                    row.element to childId
                }

            write.elementWrites.forEach { nested ->
                writeOneToManyElements(nested, nestedParents, executor)
            }
        }
    }

    private suspend fun resolveTargetIds(
        executor: QueryExecutor,
        junction: JunctionWrite<*>,
        targetIds: Set<Any?>,
    ): Set<Any?> {
        val resolved = dsl.resolveJunctionTargetIds(executor, junction, targetIds)
        return targetIds.mapTo(mutableSetOf()) { resolved.getValue(it) }
    }

    /**
     * A genuine multi-row `INSERT ... VALUES (...), (...) RETURNING ...` counterpart of [create],
     * for [commands] all at once. Unlike [create]'s own single-row [buildCreateQuery] (a per-row
     * derived-table join for [createFkResolves]), this pre-resolves every command's own root-level
     * FK value via [resolveElementFkValuesBatch] first (the same batched `IN (...)` lookup
     * [writeOneToManyElements] already uses for a *nested* group's own per-element FK resolve) -
     * once resolved, the INSERT itself is a plain multi-row `INSERT ... VALUES`, no derived table
     * needed. A [createFkResolves] entry with no match for some command surfaces as
     * [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException] straight out of
     * [resolveElementFkValuesBatch], before any SQL runs at all - the caller ([createAll]) treats
     * that (like any other exception here) as "fall back to running every command through [create]
     * individually", which re-derives each command's own precise success/failure with no extra
     * logic needed on this end.
     *
     * Correlates each `RETURNING` row back to its own input command **positionally**
     * ([commands]`.zip(insertedRows)`) - relies on Postgres processing/returning a plain
     * `VALUES`-list `INSERT` (no triggers/rules) in list order, not part of the SQL standard's own
     * guarantee but the same assumption bulk-insert-returning-ids code elsewhere already relies on;
     * covered by a real-Postgres persistence-testkit test rather than re-derived here.
     *
     * Assumes every command in [commands] produces the same [createMapper]-mapped column set (true
     * for every generated `${Entity}CreateMapper` - create has no `PatchField` "unset" concept the
     * way an update command does) - [buildBatchInsertReturningQuery] itself would otherwise
     * silently misalign columns/values row to row.
     */
    private suspend fun createAllOptimized(commands: List<C>): List<T> {
        return transactionalExecutor.withTransaction { txExecutor ->
            val ownedChildValuesPerCommand = commands.map { writeOwnedChildRows(txExecutor, it) }

            val mapped = commands.map { createMapper.map(it) }

            val fkValuesPerCommand =
                resolveElementFkValuesBatch(dsl, txExecutor, createFkResolves, commands)

            val plainColumns =
                mapped
                    .first()
                    .values.keys
                    .toList()
            val ownedChildColumns = createOwnedChildWrites.map { it.parentFkColumn }

            val fkColumns = createFkResolves.map { it.fkColumn }

            val columns: List<Field<*>> = plainColumns + ownedChildColumns + fkColumns

            val valueRows: List<List<Any?>> =
                commands.indices.map { index ->
                    columns.map { column ->
                        when {
                            column in ownedChildValuesPerCommand[index] -> {
                                ownedChildValuesPerCommand[index].getValue(column)
                            }
                            column in fkValuesPerCommand[index] -> {
                                fkValuesPerCommand[index].getValue(column)
                            }
                            else -> {
                                mapped[index].values[column]
                            }
                        }
                    }
                }

            val parentRootColumns =
                (
                    createJunctionWrites.map { it.parentRootColumn } +
                        createOneToManyWrites.map { it.parentRootColumn }
                ).distinct()
            val idReturning =
                fieldMapping.fields
                    .getValue(idField.name)
                    .field
                    .`as`(idField.name)
            val returningFields =
                listOf(idReturning) + parentRootColumns.map { it.`as`(junctionRootAlias(it)) }

            val insertedRows =
                txExecutor.executeAndGetAll(
                    dsl.buildBatchInsertReturningQuery(table, columns, valueRows, returningFields),
                )
            check(insertedRows.size == commands.size) {
                "batch INSERT ... RETURNING produced ${insertedRows.size} rows, " +
                    "expected ${commands.size}"
            }

            val parents: List<Pair<C, RowContext>> = commands.zip(insertedRows)
            writeJunctionRowsBatch(txExecutor, parents)
            writeOneToManyRowsBatch(txExecutor, parents)

            val rootIds = insertedRows.map { extractRootId(it) }

            val parts = queryTranslator.translateByIds(idField, rootIds, fieldMapping)
            val rows = txExecutor.executeAndGetAll(dsl.selectByIdsParts(table, parts))
            val byId = rows.associateBy { extractRootId(it) }

            rootIds.map { id ->
                val row =
                    byId[id]
                        ?: error(
                            "bulk re-select after batch INSERT is missing row for id " +
                                "'$id' - this should be impossible",
                        )
                rowMapper.map(row)
            }
        }
    }

    /**
     * [writeJunctionRows]'s multi-parent counterpart - instead of one [buildBatchInsertQuery] call
     * per parent per junction, folds every parent's own target-id rows for a given junction into
     * **one** call, and batches [resolveJunctionTargetIds] across every parent's own target ids too
     * (one `SELECT ... IN (...)` per junction, not one per parent) - the actual point of a
     * root-level batch write: O(junction count) statements regardless of how many [parents] there
     * are, not O(parents x junctions).
     */
    private suspend fun writeJunctionRowsBatch(
        executor: QueryExecutor,
        parents: List<Pair<C, RowContext>>,
    ) {
        createJunctionWrites.forEach { junction ->
            data class ParentTargets(
                val parentRootValue: Any?,
                val targetIds: Set<Any?>,
            )
            val perParent =
                parents.mapNotNull { (command, insertedRow) ->
                    val targetIds = junction.mapper.targetIds(command) ?: return@mapNotNull null
                    val parentRootValue =
                        insertedRow.rawOrNull(junctionRootAlias(junction.parentRootColumn))
                            ?: error(
                                "junction root column '${junction.parentRootColumn.name}' " +
                                    "was not returned by INSERT ... RETURNING - this should be impossible",
                            )
                    ParentTargets(parentRootValue, targetIds)
                }

            if (perParent.isEmpty()) return@forEach
            val unionTargetIds = perParent.flatMapTo(mutableSetOf()) { it.targetIds }

            if (unionTargetIds.isEmpty()) return@forEach
            val resolved = dsl.resolveJunctionTargetIds(executor, junction, unionTargetIds)
            val columns: List<Field<*>> = listOf(junction.rootColumn, junction.targetColumn)
            val rows =
                perParent.flatMap { parent ->
                    parent.targetIds.map { targetId ->
                        listOf(parent.parentRootValue, resolved.getValue(targetId))
                    }
                }

            if (rows.isEmpty()) return@forEach
            executor.executeUpdate(dsl.buildBatchInsertQuery(junction.table, columns, rows))
        }
    }

    /**
     * [writeOneToManyRows]'s multi-parent counterpart - builds the full [parents] list up front
     * (every root's own command/parentRootValue pair) and passes it to [writeOneToManyElements]
     * **once** per [createOneToManyWrites] entry, instead of once per parent. Needs no change to
     * [writeOneToManyElements] itself - it already batches every sibling at a given depth into one
     * [buildBatchInsertQuery] call regardless of how many [parents] it's handed (see its own kdoc);
     * today's single-root [writeOneToManyRows] simply always calls it with a one-element list.
     */
    private suspend fun writeOneToManyRowsBatch(
        executor: QueryExecutor,
        parents: List<Pair<C, RowContext>>,
    ) {
        createOneToManyWrites.forEach { write ->
            val writeParents =
                parents.map { (command, insertedRow) ->
                    val parentRootValue =
                        insertedRow.rawOrNull(junctionRootAlias(write.parentRootColumn))
                            ?: error(
                                "one-to-many parent root column '${write.parentRootColumn.name}' " +
                                    "was not returned by INSERT ... RETURNING - this should be impossible",
                            )
                    command to parentRootValue
                }

            writeOneToManyElements(write, writeParents, executor)
        }
    }

    /**
     * Tries [createAllOptimized] - one multi-row `INSERT`, batched junction/one-to-many writes, one
     * bulk re-select - and falls back to running every command through [create] one at a time, only
     * if that whole attempt throws. A single-row `INSERT ... VALUES` statement is all-or-nothing -
     * if it fails, no rows were inserted and there's no way to tell which of [commands] would have
     * succeeded on their own - so unlike [JooqUpdateAccess.updateAll]/[JooqDeleteAccess.deleteAll]
     * (whose `WHERE id IN (...)` shape naturally tells successes from failures apart without an
     * exception), a failure here re-runs *every* command, not just the bad one(s); each one's own
     * [create] call still reports its own precise success/failure, so the caller never loses that
     * information - only the "batched into one statement" optimization is lost for that one call.
     */
    override suspend fun createAll(commands: List<C>): BatchResult<T> {
        if (commands.isEmpty()) return BatchResult(emptyList())
        return runCatchingNonCancellation { createAllOptimized(commands) }
            .fold(
                onSuccess = { values -> BatchResult(values.map { BatchItemResult.Success(it) }) },
                onFailure = { BatchResult(commands.map { itemResultOf { create(it) } }) },
            )
    }
}
