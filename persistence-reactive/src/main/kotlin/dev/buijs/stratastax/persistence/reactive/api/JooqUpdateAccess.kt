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
import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.spi.BatchItemResult
import dev.buijs.stratastax.persistence.core.spi.BatchResult
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.core.spi.UpdateAccess
import dev.buijs.stratastax.persistence.core.spi.UpdateBatchItem
import dev.buijs.stratastax.persistence.core.spi.UpdateMapper
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.reactive.app.BatchUpdateRow
import dev.buijs.stratastax.persistence.reactive.app.GroupFkResolve
import dev.buijs.stratastax.persistence.reactive.app.JunctionWrite
import dev.buijs.stratastax.persistence.reactive.app.OneToManyUpdateWrite
import dev.buijs.stratastax.persistence.reactive.app.UpdateValues
import dev.buijs.stratastax.persistence.reactive.app.VersionIncrementStrategy
import dev.buijs.stratastax.persistence.reactive.app.buildBatchInsertQuery
import dev.buijs.stratastax.persistence.reactive.app.buildBatchUpdateQuery
import dev.buijs.stratastax.persistence.reactive.app.buildBatchUpdateReturningQuery
import dev.buijs.stratastax.persistence.reactive.app.buildUpdateQuery
import dev.buijs.stratastax.persistence.reactive.app.cascadeRemoveElementWrites
import dev.buijs.stratastax.persistence.reactive.app.deleteOwnedChildRows
import dev.buijs.stratastax.persistence.reactive.app.junctionRootAlias
import dev.buijs.stratastax.persistence.reactive.app.readOwnedChildIdsFor
import dev.buijs.stratastax.persistence.reactive.app.resolveElementFkValuesBatch
import dev.buijs.stratastax.persistence.reactive.app.resolveJunctionTargetIds
import dev.buijs.stratastax.persistence.reactive.app.selectByIdParts
import dev.buijs.stratastax.persistence.reactive.app.selectByIdsParts
import dev.buijs.stratastax.persistence.reactive.app.throwNotFoundOrStaleVersion
import dev.buijs.stratastax.persistence.reactive.app.throwOnMissingFkReference
import dev.buijs.stratastax.persistence.reactive.app.writeOwnedChildRow
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Generic update-side mixin a generated `${Entity}UpdateAccess` implements. [versionColumn] is
 * `null` for an entity with no `version(...)` configured - the `WHERE` clause then degrades
 * gracefully to `id = ?` alone (last-write-wins), so a `RETURNING`-less match (0 affected rows) can
 * only mean "no such id" there, surfacing as
 * `dev.buijs.stratastax.persistence.core.api.EntityNotFoundException`, no extra query needed. When
 * [versionColumn] is set, 0 affected rows is ambiguous on its own between "no such id" and "stale
 * version" - `throwNotFoundOrStaleVersion` (`FkResolveSupport.kt`) resolves that with one extra
 * diagnostic `exists()` query, paid only on this already-exceptional path:
 * `EntityNotFoundException` when the id genuinely doesn't exist, [OptimisticLockException] when it
 * does but the version didn't match. When [versionColumn] is set, every update also atomically
 * writes its new value via [versionIncrementStrategy] (`+ 1` by default, in the same statement as
 * the WHERE-clause check - see [VersionIncrementStrategy] for how a consumer plugs in a different
 * strategy)
 * - without this, the column would never change and two concurrent updates against the same
 *   `expectedVersion` would both succeed instead of the second one failing as it should.
 *
 * The `UPDATE ... RETURNING` row itself is only used to detect whether the write matched (0 rows ->
 * `throwNotFoundOrStaleVersion`) - the actual [T] mapped back to the caller comes from a re-select
 * through [queryTranslator]/ [fieldMapping] afterward, the same aliased/joined/MULTISET projection
 * `JooqReadAccess.findById` uses (a bare `RETURNING *` row only has the root table's raw, unaliased
 * columns, which is wrong for any entity with a joined field or a 1:N group) - run inside the same
 * [transactionalExecutor] transaction as the write so both see a consistent state. `RETURNING`
 * itself selects only [idColumn], aliased as [idField]'s own name (see [buildUpdateQuery]) rather
 * than a bare `RETURNING *` - not because `update()` reads any data off this row today, but so it
 * never becomes the same trap [JooqCreateAccess]'s `extractRootId` once was (a raw `RETURNING *`
 * row's labels are physical column names, not `EntityField` names, which can differ for a field
 * bound to a differently-named column).
 *
 * [updateJunctionWrites] covers a many-to-many `junction<...> { update(...) }` relation, exactly
 * like [JooqCreateAccess]'s own `createJunctionWrites` - except there's no create-time "read the
 * new id back" problem here, [id] is already known. Each junction is synced by delete-then-reinsert
 * (simple and always correct, if not maximally efficient) inside the same transaction as the root
 * row's own `UPDATE` and the re-select.
 *
 * Named `updateJunctionWrites` rather than the shorter `junctionWrites` so a single generated
 * repository can implement both this and [JooqCreateAccess] - which needs its own,
 * differently-typed `List<JunctionWrite<C>>` - without a Kotlin property clash (`C` and `U` are
 * unrelated types, so one property name can't carry both).
 *
 * [updateFkResolves] covers a 1:1 group whose identity field carries an `update(...)` binding - see
 * [JooqCreateAccess]'s own `createFkResolves` kdoc for the exact mechanics (resolved inline, as a
 * joined derived table in the same `UPDATE ... SET ... FROM` statement as the write itself - no
 * separate round trip).
 */
interface JooqUpdateAccess<ID, U, T> : UpdateAccess<ID, U, T> {

    val dsl: DSLContext
    val table: Table<*>
    val rowMapper: RowMapper<T>
    val queryExecutor: QueryExecutor
    val updateMapper: UpdateMapper<U, UpdateValues>
    val idColumn: TableField<*, *>
    val versionColumn: TableField<*, *>?

    /**
     * Default is only ever invoked when [versionColumn] is non-null (see [update] below), so an
     * entity with no version field needs no wiring for this at all - only entities *with* one get
     * it as a real constructor-injected dependency (see `UpdateAccessImplRenderer`).
     */
    val versionIncrementStrategy: VersionIncrementStrategy
        get() = VersionIncrementStrategy { column -> DSL.field("{0} + 1", column.dataType, column) }

    val fieldMapping: JooqFieldMapping
    val queryTranslator: JooqSearchQueryTranslator
    val idField: EntityField
    val transactionalExecutor: TransactionalExecutor

    val updateJunctionWrites: List<JunctionWrite<U>>
        get() = emptyList()

    val updateFkResolves: List<GroupFkResolve<U>>
        get() = emptyList()

    /**
     * Covers a top-level *managed* direct-FK `oneToManyGroup<...> { publicApi { update(...) } }`
     * relation - see [OneToManyUpdateWrite]'s own kdoc for the diff-based
     * upsert/delete-or-soft-delete semantics.
     */
    val updateOneToManyWrites: List<OneToManyUpdateWrite<U, *>>
        get() = emptyList()

    /** Converts the [ID] this interface is generic over to the id column's value type. */
    fun mapId(id: ID): Any = id as Any

    @Suppress("UNCHECKED_CAST")
    override suspend fun update(
        id: ID,
        command: U,
        expectedVersion: Any?,
    ): T {
        return transactionalExecutor.withTransaction { txExecutor ->
            val values = updateMapper.map(command)
            var condition = (idColumn as TableField<Record, Any?>).eq(mapId(id))
            var setValues = values.values
            versionColumn?.let { vc ->
                condition = condition.and((vc as TableField<Record, Any?>).eq(expectedVersion))
                setValues = setValues + (vc to versionIncrementStrategy.next(vc))
            }

            val parentRootColumns =
                (
                    updateJunctionWrites.map { it.parentRootColumn } +
                        updateOneToManyWrites.map { it.parentRootColumn }
                ).distinct()

            val query =
                dsl.buildUpdateQuery(
                    table,
                    setValues,
                    condition,
                    updateFkResolves,
                    command,
                    idColumn.`as`(idField.name),
                    parentRootColumns.map { it.`as`(junctionRootAlias(it)) },
                )

            val updatedRow =
                txExecutor.executeReturning(query)
                    ?: run {
                        dsl.throwOnMissingFkReference(txExecutor, updateFkResolves, command)
                        dsl.throwNotFoundOrStaleVersion(
                            txExecutor,
                            table,
                            idColumn,
                            versionColumn,
                            mapId(id),
                            expectedVersion,
                        )
                    }

            resyncJunctionRows(txExecutor, command, updatedRow)
            resyncOneToManyRows(txExecutor, command, updatedRow)

            val parts = queryTranslator.translateById(idField, mapId(id), fieldMapping)
            val row =
                txExecutor.executeAndGetFirst(dsl.selectByIdParts(table, parts))
                    ?: error("re-select after UPDATE produced no row - this should be impossible")
            rowMapper.map(row)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun resyncJunctionRows(
        executor: QueryExecutor,
        command: U,
        updatedRow: RowContext,
    ) {
        updateJunctionWrites.forEach { junction ->
            val targetIds = junction.mapper.targetIds(command) ?: return@forEach
            val parentRootValue =
                updatedRow.rawOrNull(junctionRootAlias(junction.parentRootColumn))
                    ?: error(
                        "junction root column '${junction.parentRootColumn.name}' was " +
                            "not returned by UPDATE ... RETURNING - this should be impossible",
                    )
            val rootCol = junction.rootColumn as TableField<Record, Any?>
            executor.executeUpdate(
                dsl.deleteFrom(junction.table).where(rootCol.eq(parentRootValue)),
            )
            val resolvedTargetIds = resolveTargetIds(executor, junction, targetIds)
            if (resolvedTargetIds.isEmpty()) return@forEach
            val columns: List<Field<*>> = listOf(junction.rootColumn, junction.targetColumn)
            val rows = resolvedTargetIds.map { targetId -> listOf(parentRootValue, targetId) }

            executor.executeUpdate(dsl.buildBatchInsertQuery(junction.table, columns, rows))
        }
    }

    private suspend fun resyncOneToManyRows(
        executor: QueryExecutor,
        command: U,
        updatedRow: RowContext,
    ) {
        updateOneToManyWrites.forEach { write ->
            val parentRootValue =
                updatedRow.rawOrNull(junctionRootAlias(write.parentRootColumn))
                    ?: error(
                        "one-to-many parent root column '${write.parentRootColumn.name}' " +
                            "was not returned by UPDATE ... RETURNING - this should be impossible",
                    )
            resyncOneToManyElements(write, listOf(command to parentRootValue), executor)
        }
    }

    /**
     * Generic over both [U2] and [E] for the same reason [JooqCreateAccess.writeOneToManyElements]
     * is - not tied to this interface's own `U`, so this same function can recurse into
     * [OneToManyUpdateWrite.elementWrites], where the "command" for that nested write is [E] (this
     * level's own element type). Diffs the incoming [write]'s own [OneToManyUpdateWrite.elements] -
     * across every one of [parents] at once, the same sibling-batching
     * [JooqCreateAccess.writeOneToManyElements] does - against the child rows currently in the
     * database (matched by [OneToManyUpdateWrite.childIdentityColumn]): every matched id folds into
     * one [buildBatchUpdateQuery] call, every unmatched id into one [buildBatchInsertQuery] call (a
     * child added during this same `update()` call, its own id generated the same way
     * [OneToManyCreateWrite]'s would be), and an existing child row whose id isn't present in *any*
     * parent's own incoming elements is soft-deleted (via [OneToManyUpdateWrite.softDeleteColumn],
     * `CURRENT_TIMESTAMP` - same convention [JooqDeleteAccess] already uses) or hard-`DELETE`d.
     * Either way, [OneToManyUpdateWrite.elementWrites] resyncs right alongside its own parent
     * elements - every matched/new element's own nested children via a further recursive
     * [resyncOneToManyElements] call (using [OneToManyUpdateWrite.elementId], already known either
     * way, as their own `parentRootValue`), a *removed* element's own nested children via
     * [cascadeRemoveElementWrites] - called *before* the parent rows themselves are removed, so a
     * hard-`DELETE` never trips a live FK constraint from a still-present child row.
     *
     * A parent whose own [OneToManyUpdateWrite.elements] call returns `null` (see that property's
     * own kdoc - a `PatchField`-typed group binding left
     * [dev.buijs.stratastax.entity.core.api.PatchField.Unset]) is filtered out of [parents]
     * entirely, *before* the existing-rows lookup below - not just treated as "zero incoming
     * elements" for that parent, which would otherwise be indistinguishable from an explicit
     * "remove every child" and wrongly delete every row still attached to it.
     *
     * [OneToManyUpdateWrite.replaceAll] short-circuits the id-based diff this function otherwise
     * performs: `matchedIndices` is forced empty (every incoming element goes through the ordinary
     * "new" `INSERT` path below, regardless of whether its own [OneToManyUpdateWrite.elementId]
     * happens to coincide with an existing row's) and `removedIds` is forced to every existing id
     * (not just ones absent from the incoming set). Everything else
     * - the batched `INSERT`, the `elementWrites` recursion, and [cascadeRemoveElementWrites] - is
     *   unchanged code, reused as-is. Under `replaceAll` the removal runs *before* the insert: the
     *   new rows may repeat a natural key of the old ones (the same player in a match), which a
     *   unique constraint refuses while the old row exists. The ordinary diff keeps
     *   insert-before-remove.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <U2, E> resyncOneToManyElements(
        write: OneToManyUpdateWrite<U2, E>,
        parents: List<Pair<U2, Any?>>,
        executor: QueryExecutor,
    ) {
        val activeParents = parents.filter { (command, _) -> write.elements(command) != null }

        if (activeParents.isEmpty()) return

        val parentCol = write.parentColumn as TableField<Record, Any?>
        val identityCol = write.childIdentityColumn as TableField<Record, Any?>

        data class Row(
            val element: E,
            val elementId: Any,
            val parentRootValue: Any?,
        )
        val rows =
            activeParents.flatMap { (command, parentRootValue) ->
                write.elements(command)!!.map { element ->
                    Row(element, write.elementId(element), parentRootValue)
                }
            }

        val parentRootValues = activeParents.map { it.second }

        val existingIds =
            executor
                .executeAndGetAll(
                    dsl
                        .select(write.childIdentityColumn)
                        .from(write.childTable)
                        .where(parentCol.`in`(parentRootValues)),
                ).mapNotNull { it.rawOrNull(write.childIdentityColumn.name) }
                .toSet()

        val incomingIds = rows.map { it.elementId }.toSet()

        // Removes the children that are not (or, under replaceAll, no longer) wanted.
        suspend fun removeElements(removedIds: Set<Any>) {
            if (removedIds.isEmpty()) return
            if (write.elementWrites.isNotEmpty()) {
                cascadeRemoveElementWrites(dsl, executor, write.elementWrites, removedIds)
            }

            // Read every owned child's own id *before* the about-to-be-removed
            // rows are touched below - once they're gone (or, for a hard delete,
            // gone for good), their own FK value can no longer be read back. The
            // actual owned-child delete only happens *after*, via
            // deleteOwnedChildRows - see that function's own kdoc for why this
            // split (rather than one combined call) is required here.
            val ownedChildIds =
                readOwnedChildIdsFor(
                    dsl,
                    executor,
                    write.elementOwnedChildWrites,
                    write.childTable,
                    identityCol.`in`(removedIds),
                )
            val softDeleteCol = write.softDeleteColumn as? TableField<Record, Any?>
            if (softDeleteCol != null) {
                executor.executeUpdate(
                    dsl
                        .update(write.childTable)
                        .set(softDeleteCol, DSL.currentOffsetDateTime())
                        .where(identityCol.`in`(removedIds)),
                )
            } else {
                executor.executeUpdate(
                    dsl.deleteFrom(write.childTable).where(identityCol.`in`(removedIds)),
                )
            }

            deleteOwnedChildRows(dsl, executor, ownedChildIds)
        }

        // Under replaceAll every existing child goes, and it goes first: the new rows may repeat a
        // natural key of the old ones (e.g. the same player in a match), which a unique constraint
        // would refuse while the old row still exists.
        if (write.replaceAll) removeElements(existingIds)

        if (rows.isNotEmpty()) {
            val mapped = rows.map { it to write.elementMapper.map(it.element) }

            val fkValuesPerRow =
                resolveElementFkValuesBatch(
                    dsl,
                    executor,
                    write.elementFkResolves,
                    rows.map { it.element },
                )
            val fkColumns: List<Field<*>> = write.elementFkResolves.map { it.fkColumn }

            val plainColumns: List<Field<*>> =
                mapped
                    .first()
                    .second.values.keys
                    .toList()

            val matchedIndices =
                if (write.replaceAll) {
                    emptyList()
                } else {
                    mapped.indices.filter { rows[it].elementId in existingIds }
                }

            val newIndices =
                if (write.replaceAll) {
                    mapped.indices.toList()
                } else {
                    mapped.indices.filter { rows[it].elementId !in existingIds }
                }

            if (matchedIndices.isNotEmpty()) {
                val columns = plainColumns + write.parentColumn + fkColumns
                val rowsByIdentity: List<Pair<Any?, List<Any?>>> =
                    matchedIndices.map { index ->
                        val (row, values) = mapped[index]
                        val rowValues =
                            columns.map { column ->
                                when {
                                    column == write.parentColumn -> {
                                        row.parentRootValue
                                    }
                                    column in fkValuesPerRow[index] -> {
                                        fkValuesPerRow[index].getValue(column)
                                    }
                                    else -> {
                                        values.values[column]
                                    }
                                }
                            }

                        row.elementId to rowValues
                    }

                val affected =
                    executor.executeUpdate(
                        dsl.buildBatchUpdateQuery(
                            write.childTable,
                            write.childIdentityColumn,
                            columns,
                            rowsByIdentity,
                        ),
                    )
                check(affected == matchedIndices.size) {
                    "one-to-many batch UPDATE affected $affected rows, expected ${matchedIndices.size}"
                }
            }

            if (newIndices.isNotEmpty()) {
                // One at a time, per element - mirrors
                // JooqCreateAccess.writeOneToManyElements's own
                // ownedChildValuesPerRow step exactly: each owned child is a
                // fresh row of its own, inserted before this element's own
                // row joins the batch INSERT below, so its newly-generated id
                // can be folded into that same row (see
                // OwnedChildCreateWrite's own kdoc). Keyed by index (not a
                // plain list, unlike the create side) since newIndices is a
                // filtered subset of mapped.indices, not every element.
                val ownedChildValuesPerRow: Map<Int, Map<Field<*>, Any?>> =
                    newIndices.associateWith { index ->
                        write.elementOwnedChildCreateWrites
                            .mapNotNull { ownedWrite ->
                                writeOwnedChildRow(
                                    dsl,
                                    executor,
                                    ownedWrite,
                                    mapped[index].first.element,
                                )
                            }.toMap()
                    }

                // A new row starts at the initial version, as on create.
                val versionColumn = write.versionColumn?.takeIf { write.initialVersion != null }

                val columns =
                    plainColumns +
                        write.parentColumn +
                        write.childIdentityColumn +
                        fkColumns +
                        write.elementOwnedChildCreateWrites.map { it.parentFkColumn } +
                        listOfNotNull(versionColumn)
                val valueRows: List<List<Any?>> =
                    newIndices.map { index ->
                        val (row, values) = mapped[index]
                        columns.map { column ->
                            when {
                                column == write.parentColumn -> {
                                    row.parentRootValue
                                }
                                column == write.childIdentityColumn -> {
                                    row.elementId
                                }
                                column == versionColumn -> {
                                    write.initialVersion!!.initial()
                                }
                                column in fkValuesPerRow[index] -> {
                                    fkValuesPerRow[index].getValue(column)
                                }
                                column in ownedChildValuesPerRow.getValue(index) -> {
                                    ownedChildValuesPerRow.getValue(index).getValue(column)
                                }
                                else -> {
                                    values.values[column]
                                }
                            }
                        }
                    }

                val affected =
                    executor.executeUpdate(
                        dsl.buildBatchInsertQuery(write.childTable, columns, valueRows),
                    )
                check(affected == newIndices.size) {
                    "one-to-many batch INSERT affected $affected rows, expected ${newIndices.size}"
                }
            }

            if (write.elementWrites.isNotEmpty()) {
                val nestedParents: List<Pair<E, Any?>> = rows.map { it.element to it.elementId }

                write.elementWrites.forEach { nested ->
                    resyncOneToManyElements(nested, nestedParents, executor)
                }
            }
        }

        if (!write.replaceAll) removeElements(existingIds - incomingIds)
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
     * Groups [items] so rows with the same active column set can share one
     * [buildBatchUpdateReturningQuery] call.
     */
    private data class UpdateShapeKey<U>(
        val plainColumns: List<Field<*>>,
        val activeResolves: List<GroupFkResolve<U>>,
    )

    /**
     * A genuine multi-row `UPDATE ... SET ... WHERE id IN (...) RETURNING ...` counterpart of
     * [update], for [items] all at once. Unlike a one-to-many group's own element mapper (which
     * never applies `PatchField` semantics - see [buildBatchUpdateQuery]'s own kdoc), a root-level
     * [updateMapper] genuinely can produce a different column set per command (any
     * `PatchField`-typed field left [dev.buijs.stratastax.entity.core.api.PatchField.Unset] is
     * simply absent from [UpdateValues]), and [updateFkResolves]'s own [GroupFkResolve.skip] can do
     * the same for a resolved FK column - so [items] is first grouped by its own "shape"
     * ([UpdateShapeKey]: the exact set of plain columns plus which [updateFkResolves] entries are
     * active) and one batched `UPDATE` is issued per group, not one for the whole call. The common
     * case (every command in one `updateAll` call patches the same fields) still costs one
     * statement; a maximally heterogeneous batch degrades to one statement per distinct shape,
     * never per row.
     *
     * Correlates matched rows back to their own input **by id**, not position - [returningFields]
     * always includes [idColumn] aliased as [idField]'s own name, so unlike
     * [JooqCreateAccess.createAllOptimized]'s batch `INSERT` (which has no id to key off yet),
     * there's no ordering assumption to make here at all. An id/version combination with no
     * matching row simply isn't a key in the returned map - the caller ([updateAll]) re-runs
     * exactly those through [update] individually to get the precise
     * [dev.buijs.stratastax.persistence.core.api.EntityNotFoundException]/
     * [OptimisticLockException], the same diagnosis a single-row `update()` already does; every
     * match keeps this optimized attempt's own result, never redone.
     */
    private suspend fun updateAllOptimized(items: List<UpdateBatchItem<ID, U>>): Map<Any?, T> {
        return transactionalExecutor.withTransaction { txExecutor ->
            val mapped = items.map { updateMapper.map(it.command) }

            val shapeKeys =
                items.indices.map { i ->
                    UpdateShapeKey(
                        mapped[i].values.keys.toList(),
                        updateFkResolves.filterNot { it.skip(items[i].command) },
                    )
                }

            val groups: Map<UpdateShapeKey<U>, List<Int>> =
                shapeKeys.withIndex().groupBy({ it.value }) { it.index }

            val parentRootColumns =
                (
                    updateJunctionWrites.map { it.parentRootColumn } +
                        updateOneToManyWrites.map { it.parentRootColumn }
                ).distinct()
            val idReturning = idColumn.`as`(idField.name)
            val returningFields =
                listOf(idReturning) + parentRootColumns.map { it.`as`(junctionRootAlias(it)) }

            val matchedRows = mutableListOf<RowContext>()
            groups.forEach { (shape, indices) ->
                val groupItems = indices.map { items[it] }

                val groupFkValues =
                    resolveElementFkValuesBatch(
                        dsl,
                        txExecutor,
                        shape.activeResolves,
                        groupItems.map { it.command },
                    )
                val fkColumns = shape.activeResolves.map { it.fkColumn }

                val columns = shape.plainColumns + fkColumns
                val additionalSetValues: Map<Field<*>, Any?> =
                    versionColumn?.let { vc ->
                        mapOf<Field<*>, Any?>(vc to versionIncrementStrategy.next(vc))
                    } ?: emptyMap()
                val rows =
                    indices.mapIndexed { j, idx ->
                        val item = items[idx]
                        val values =
                            columns.map { column ->
                                if (column in groupFkValues[j]) {
                                    groupFkValues[j].getValue(column)
                                } else {
                                    mapped[idx].values[column]
                                }
                            }

                        BatchUpdateRow(mapId(item.id), item.expectedVersion, values)
                    }

                val query =
                    dsl.buildBatchUpdateReturningQuery(
                        table,
                        idColumn,
                        versionColumn,
                        columns,
                        rows,
                        additionalSetValues,
                        returningFields,
                    )
                matchedRows += txExecutor.executeAndGetAll(query)
            }

            val matchedRowByRawId = matchedRows.associateBy { it.rawOrNull(idField.name) }

            if (matchedRowByRawId.isEmpty()) return@withTransaction emptyMap()

            val matchedParents: List<Pair<U, RowContext>> =
                items.mapNotNull { item ->
                    matchedRowByRawId[mapId(item.id)]?.let { row -> item.command to row }
                }

            resyncJunctionRowsBatch(txExecutor, matchedParents)
            resyncOneToManyRowsBatch(txExecutor, matchedParents)

            val parts =
                queryTranslator.translateByIds(
                    idField,
                    matchedRowByRawId.keys.filterNotNull(),
                    fieldMapping,
                )
            val reselected = txExecutor.executeAndGetAll(dsl.selectByIdsParts(table, parts))
            reselected.associate { it.rawOrNull(idField.name) to rowMapper.map(it) }
        }
    }

    /**
     * [resyncJunctionRows]'s multi-parent counterpart - see
     * [JooqCreateAccess.writeJunctionRowsBatch]'s own kdoc for why this batches across parents.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun resyncJunctionRowsBatch(
        executor: QueryExecutor,
        parents: List<Pair<U, RowContext>>,
    ) {
        updateJunctionWrites.forEach { junction ->
            data class ParentTargets(
                val parentRootValue: Any?,
                val targetIds: Set<Any?>,
            )
            val perParent =
                parents.mapNotNull { (command, updatedRow) ->
                    val targetIds = junction.mapper.targetIds(command) ?: return@mapNotNull null
                    val parentRootValue =
                        updatedRow.rawOrNull(junctionRootAlias(junction.parentRootColumn))
                            ?: error(
                                "junction root column '${junction.parentRootColumn.name}' " +
                                    "was not returned by UPDATE ... RETURNING - this should be impossible",
                            )
                    ParentTargets(parentRootValue, targetIds)
                }

            if (perParent.isEmpty()) return@forEach
            val rootCol = junction.rootColumn as TableField<Record, Any?>
            executor.executeUpdate(
                dsl
                    .deleteFrom(junction.table)
                    .where(rootCol.`in`(perParent.map { it.parentRootValue })),
            )
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
     * [resyncOneToManyRows]'s multi-parent counterpart - needs no change to
     * [resyncOneToManyElements] itself, see [JooqCreateAccess.writeOneToManyRowsBatch]'s own kdoc.
     */
    private suspend fun resyncOneToManyRowsBatch(
        executor: QueryExecutor,
        parents: List<Pair<U, RowContext>>,
    ) {
        updateOneToManyWrites.forEach { write ->
            val writeParents =
                parents.map { (command, updatedRow) ->
                    val parentRootValue =
                        updatedRow.rawOrNull(junctionRootAlias(write.parentRootColumn))
                            ?: error(
                                "one-to-many parent root column '${write.parentRootColumn.name}' " +
                                    "was not returned by UPDATE ... RETURNING - this should be impossible",
                            )
                    command to parentRootValue
                }

            resyncOneToManyElements(write, writeParents, executor)
        }
    }

    /**
     * Tries [updateAllOptimized] - one batched `UPDATE ... RETURNING` per distinct command "shape",
     * batched junction/one-to-many resync, one bulk re-select - and, for every input whose id
     * didn't come back in that attempt's own result (wrong id, stale version), falls back to a
     * single [update] call to get the precise exception; a match from the optimized attempt is
     * never redone. Only a structural failure (the optimized attempt throwing before producing
     * *any* result, e.g. every command sharing one shape's [resolveElementFkValuesBatch] call
     * failing) falls back *every* item to [update], the same all-or-nothing degradation
     * [JooqCreateAccess.createAll] always has.
     */
    override suspend fun updateAll(items: List<UpdateBatchItem<ID, U>>): BatchResult<T> {
        if (items.isEmpty()) return BatchResult(emptyList())
        return runCatchingNonCancellation { updateAllOptimized(items) }
            .fold(
                onSuccess = { successesByRawId ->
                    BatchResult(
                        items.map { item ->
                            successesByRawId[mapId(item.id)]?.let { BatchItemResult.Success(it) }
                                ?: itemResultOf {
                                    update(item.id, item.command, item.expectedVersion)
                                }
                        },
                    )
                },
                onFailure = {
                    BatchResult(
                        items.map {
                            itemResultOf {
                                update(
                                    it.id,
                                    it.command,
                                    it.expectedVersion,
                                )
                            }
                        },
                    )
                },
            )
    }
}
