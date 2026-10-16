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

import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.spi.BatchItemResult
import dev.buijs.stratastax.persistence.core.spi.BatchResult
import dev.buijs.stratastax.persistence.core.spi.DeleteAccess
import dev.buijs.stratastax.persistence.core.spi.DeleteBatchItem
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.reactive.app.JunctionDeleteWrite
import dev.buijs.stratastax.persistence.reactive.app.OneToManyUpdateWrite
import dev.buijs.stratastax.persistence.reactive.app.OwnedChildDeleteWrite
import dev.buijs.stratastax.persistence.reactive.app.cascadeRemoveElementWrites
import dev.buijs.stratastax.persistence.reactive.app.deleteOwnedChildRows
import dev.buijs.stratastax.persistence.reactive.app.throwNotFoundOrStaleVersion
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Generic delete-side mixin - see [JooqUpdateAccess]'s kdoc for [versionColumn].
 *
 * [softDeleteColumn] makes delete configurable per entity, as some entities are hard-deleted and
 * others aren't: `null` (the default) means a real `DELETE`; set it to the entity's soft-delete
 * timestamp column (a field configured `generated(onDelete = ...)` in the DSL) to turn [delete]
 * into an `UPDATE` that sets that column to `CURRENT_TIMESTAMP` instead - the row is never
 * physically removed. Either way [versionColumn] (if configured) is checked and atomically
 * incremented exactly as in [JooqUpdateAccess].
 *
 * [deleteOwnedChildWrites] covers a top-level *owned* 1:1 `@PersistenceJoin(owned = true)` group
 * (see [OwnedChildCreateWrite]'s own kdoc) - each owned child's own row is deleted (or
 * soft-deleted) right after the parent's own row, in the same transaction, using the parent's own
 * FK value *read before* that row is touched (see [OwnedChildDeleteWrite]'s own kdoc for why
 * parent-then-child is the only safe order here).
 *
 * [deleteOneToManyWrites] covers a top-level *managed* direct-FK `oneToManyGroup<...> { publicApi {
 * create(); update() } }` relation (e.g. `Match.participants`) - every one of the group's own child
 * rows (and, recursively, any managed group nested inside it) is removed *before* the parent row,
 * via the same [cascadeRemoveElementWrites] leaf-first cascade [JooqUpdateAccess] already uses for
 * a *removed* element during `update()`.
 *
 * [deleteJunctionWrites] covers a top-level `junction<...> { }` relation (e.g.
 * `Match.expansions`) - only the link rows in the junction's own table are removed; the junction's
 * *target* table (e.g. `EXPANSION`) is never touched, matching that relation's own read-only-target
 * contract, unless the entry's own [JunctionDeleteWrite.cascadeTarget] is set
 * (`@PersistenceJoinMany(owned = true)`), in which case the target ids are read off the link rows
 * *before* they're removed, then deleted too once unlinked.
 *
 * Both read their own [OneToManyUpdateWrite.parentRootColumn]/
 * [JunctionDeleteWrite.parentRootColumn] value off the parent row *before* it is deleted - same
 * reasoning as [deleteOwnedChildWrites]'s own [readOwnedChildIds], and the same caveat as
 * [JunctionWrite.parentRootColumn]'s own kdoc: this column is not guaranteed to equal [idColumn].
 */
interface JooqDeleteAccess<ID> : DeleteAccess<ID> {

    val dsl: DSLContext
    val table: Table<*>
    val queryExecutor: QueryExecutor

    /**
     * Runs every statement of one [delete] (the cascades, the root row and its owned children) in a
     * single transaction, so a failure halfway - e.g. a stale [versionColumn] detected only after
     * the cascades already ran - leaves nothing half-deleted. Joins the caller's transaction
     * (inside a savepoint) when there is one.
     */
    val transactionalExecutor: TransactionalExecutor
    val idColumn: TableField<*, *>
    val versionColumn: TableField<*, *>?
    val softDeleteColumn: TableField<*, *>?
        get() = null

    val deleteOwnedChildWrites: List<OwnedChildDeleteWrite>
        get() = emptyList()

    val deleteOneToManyWrites: List<OneToManyUpdateWrite<*, *>>
        get() = emptyList()

    val deleteJunctionWrites: List<JunctionDeleteWrite>
        get() = emptyList()

    /** Converts the [ID] this interface is generic over to the id column's value type. */
    fun mapId(id: ID): Any = id as Any

    @Suppress("UNCHECKED_CAST")
    override suspend fun delete(
        id: ID,
        expectedVersion: Any?,
    ) {
        transactionalExecutor.withTransaction { executor -> delete(executor, id, expectedVersion) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun delete(
        executor: QueryExecutor,
        id: ID,
        expectedVersion: Any?,
    ) {
        var condition = (idColumn as TableField<Record, Any?>).eq(mapId(id))
        versionColumn?.let { vc ->
            condition = condition.and((vc as TableField<Record, Any?>).eq(expectedVersion))
        }

        val ownedChildIds = readOwnedChildIds(executor, condition)
        val cascadeRootValues = readCascadeRootValues(executor, condition)
        cascadeRootValues?.let { row ->
            deleteJunctionWrites.forEach { junction ->
                val parentRootValue = row[junction.parentRootColumn] ?: return@forEach
                val rootCol = junction.rootColumn as TableField<Record, Any?>
                val cascadeTarget = junction.cascadeTarget
                val unlinkedTargetIds =
                    if (cascadeTarget == null) {
                        emptySet()
                    } else {
                        executor
                            .executeAndGetAll(
                                dsl
                                    .select(cascadeTarget.junctionTargetColumn)
                                    .from(junction.table)
                                    .where(rootCol.eq(parentRootValue)),
                            ).mapNotNull { it.rawOrNull(cascadeTarget.junctionTargetColumn.name) }
                            .toSet()
                    }

                executor.executeUpdate(
                    dsl.deleteFrom(junction.table).where(rootCol.eq(parentRootValue)),
                )
                if (cascadeTarget != null && unlinkedTargetIds.isNotEmpty()) {
                    val targetCol = cascadeTarget.targetColumn as TableField<Record, Any?>
                    executor.executeUpdate(
                        dsl
                            .deleteFrom(
                                cascadeTarget.table,
                            ).where(targetCol.`in`(unlinkedTargetIds)),
                    )
                }
            }

            deleteOneToManyWrites.forEach { write ->
                val parentRootValue = row[write.parentRootColumn] ?: return@forEach
                cascadeRemoveElementWrites(dsl, executor, listOf(write), setOf(parentRootValue))
            }
        }

        val affected =
            softDeleteColumn?.let { sdCol ->
                val setValues =
                    buildMap<TableField<*, *>, Any?> {
                        put(sdCol, DSL.currentOffsetDateTime())
                        versionColumn?.let { vc -> put(vc, DSL.field("{0} + 1", vc.dataType, vc)) }
                    }

                executor.executeUpdate(dsl.update(table).set(setValues).where(condition))
            } ?: executor.executeUpdate(dsl.deleteFrom(table).where(condition))

        if (affected == 0) {
            dsl.throwNotFoundOrStaleVersion(
                executor,
                table,
                idColumn,
                versionColumn,
                mapId(id),
                expectedVersion,
            )
        }

        writeOwnedChildDeletes(executor, ownedChildIds)
    }

    /**
     * Reads every [deleteOwnedChildWrites] entry's own [OwnedChildDeleteWrite.parentFkColumn] value
     * *before* [condition] is used to delete/soft-delete the parent's own row - once that row is
     * gone (or, for a hard delete, gone for good), its own FK value can no longer be read back.
     * `emptyMap()` (no query at all) when [deleteOwnedChildWrites] is empty, or when the parent row
     * itself doesn't match [condition] (nothing to cascade into either way - [delete]'s own
     * [OptimisticLockException] check afterward reports that clearly).
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun readOwnedChildIds(
        executor: QueryExecutor,
        condition: Condition,
    ): Map<OwnedChildDeleteWrite, Any?> {
        if (deleteOwnedChildWrites.isEmpty()) return emptyMap()
        val columns = deleteOwnedChildWrites.map { it.parentFkColumn }

        val row =
            executor.executeAndGetFirst(dsl.select(columns).from(table).where(condition))
                ?: return emptyMap()
        return deleteOwnedChildWrites.associateWith { row.rawOrNull(it.parentFkColumn.name) }
    }

    /**
     * Reads every [deleteOneToManyWrites]/[deleteJunctionWrites] entry's own `parentRootColumn`
     * value off the parent row, in one query, *before* [condition] is used to delete/soft-delete
     * that row - same reasoning as [readOwnedChildIds]. `null` (no query at all) when both lists
     * are empty, or when the parent row itself doesn't match [condition].
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun readCascadeRootValues(
        executor: QueryExecutor,
        condition: Condition,
    ): Map<TableField<*, *>, Any?>? {
        val columns =
            (
                deleteOneToManyWrites.map { it.parentRootColumn } +
                    deleteJunctionWrites.map { it.parentRootColumn }
            ).distinct()
        if (columns.isEmpty()) return null
        val row =
            executor.executeAndGetFirst(dsl.select(columns).from(table).where(condition))
                ?: return null
        return columns.associateWith { row.rawOrNull(it.name) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun writeOwnedChildDeletes(
        executor: QueryExecutor,
        ownedChildIds: Map<OwnedChildDeleteWrite, Any?>,
    ) {
        ownedChildIds.forEach { (write, childId) ->
            if (childId == null) return@forEach
            val childCondition = (write.childIdColumn as TableField<Record, Any?>).eq(childId)
            write.softDeleteColumn?.let { sdCol ->
                executor.executeUpdate(
                    dsl
                        .update(write.childTable)
                        .set(sdCol as TableField<Record, Any?>, DSL.currentOffsetDateTime() as Any?)
                        .where(childCondition),
                )
            } ?: executor.executeUpdate(dsl.deleteFrom(write.childTable).where(childCondition))
        }
    }

    /**
     * A genuine multi-row `DELETE ... WHERE id IN (...) RETURNING id`/`UPDATE ... SET
     * softDeleteColumn = ... WHERE id IN (...) RETURNING id` counterpart of [delete], for [items]
     * all at once. Builds one [condition] up front - `id IN (ids)`, `AND`ed with a per-row `(id =
     * idN AND version = versionN)` disjunction when [versionColumn] is set, exactly [delete]'s own
     * condition - and reuses it for the bulk owned-child/cascade-root reads *and* the final parent
     * delete/soft-delete, so a row whose version doesn't match (or whose id doesn't exist at all)
     * contributes to neither: same "cascade only what the write itself would actually touch"
     * guarantee [delete]'s own reuse of `condition` already gives a single row, just batched.
     *
     * Returns every matched row's own raw id ([idColumn]'s own value, as `RETURNING` reports it) -
     * the caller ([deleteAll]) treats any input id *not* in that set as a candidate for a precise
     * single-item [delete] call (to learn whether it was "no such id" or "stale version"), the same
     * surgical fallback [JooqUpdateAccess.updateAll] uses.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun deleteAllOptimized(items: List<DeleteBatchItem<ID>>): Set<Any?> =
        transactionalExecutor.withTransaction { executor -> deleteAllOptimized(executor, items) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun deleteAllOptimized(
        executor: QueryExecutor,
        items: List<DeleteBatchItem<ID>>,
    ): Set<Any?> {
        val idCol = idColumn as TableField<Record, Any?>
        var condition: Condition = idCol.`in`(items.map { mapId(it.id) })
        if (versionColumn != null) {
            val versionCol = versionColumn as TableField<Record, Any?>
            val perItemCondition =
                items
                    .map { item ->
                        idCol.eq(mapId(item.id)).and(versionCol.eq(item.expectedVersion))
                    }.reduce(Condition::or)
            condition = condition.and(perItemCondition)
        }

        val ownedChildByRawId = readOwnedChildIdsBatch(executor, condition)
        val cascadeRootByRawId = readCascadeRootValuesBatch(executor, condition)

        deleteJunctionWrites.forEach { junction ->
            val parentRootValues =
                cascadeRootByRawId.values.mapNotNull { it[junction.parentRootColumn] }

            if (parentRootValues.isEmpty()) return@forEach
            val rootCol = junction.rootColumn as TableField<Record, Any?>
            val cascadeTarget = junction.cascadeTarget
            val unlinkedTargetIds =
                if (cascadeTarget == null) {
                    emptySet()
                } else {
                    executor
                        .executeAndGetAll(
                            dsl
                                .select(cascadeTarget.junctionTargetColumn)
                                .from(junction.table)
                                .where(rootCol.`in`(parentRootValues)),
                        ).mapNotNull { it.rawOrNull(cascadeTarget.junctionTargetColumn.name) }
                        .toSet()
                }

            executor.executeUpdate(
                dsl.deleteFrom(junction.table).where(rootCol.`in`(parentRootValues)),
            )
            if (cascadeTarget != null && unlinkedTargetIds.isNotEmpty()) {
                val targetCol = cascadeTarget.targetColumn as TableField<Record, Any?>
                executor.executeUpdate(
                    dsl.deleteFrom(cascadeTarget.table).where(targetCol.`in`(unlinkedTargetIds)),
                )
            }
        }

        deleteOneToManyWrites.forEach { write ->
            val parentRootValues =
                cascadeRootByRawId.values.mapNotNull { it[write.parentRootColumn] }.toSet()
            if (parentRootValues.isEmpty()) return@forEach
            cascadeRemoveElementWrites(dsl, executor, listOf(write), parentRootValues)
        }

        val matchedRows =
            softDeleteColumn?.let { sdCol ->
                val setValues =
                    buildMap<TableField<*, *>, Any?> {
                        put(sdCol, DSL.currentOffsetDateTime())
                        versionColumn?.let { vc -> put(vc, DSL.field("{0} + 1", vc.dataType, vc)) }
                    }

                executor.executeAndGetAll(
                    dsl
                        .update(table)
                        .set(setValues)
                        .where(condition)
                        .returning(idColumn),
                )
            }
                ?: executor.executeAndGetAll(
                    dsl.deleteFrom(table).where(condition).returning(idColumn),
                )

        val matchedIds = matchedRows.mapNotNull { it.rawOrNull(idColumn.name) }.toSet()
        if (matchedIds.isEmpty()) return emptySet()

        val matchedOwnedChildIds: Map<OwnedChildDeleteWrite, Set<Any?>> =
            deleteOwnedChildWrites.associateWith { write ->
                matchedIds.mapNotNull { rawId -> ownedChildByRawId[rawId]?.get(write) }.toSet()
            }

        deleteOwnedChildRows(dsl, executor, matchedOwnedChildIds)

        return matchedIds
    }

    /**
     * [readOwnedChildIds]'s multi-row counterpart - one `SELECT id, parentFkColumn... FROM table
     * WHERE [condition]` for every id at once, keyed by each matched row's own raw id.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun readOwnedChildIdsBatch(
        executor: QueryExecutor,
        condition: Condition,
    ): Map<Any?, Map<OwnedChildDeleteWrite, Any?>> {
        if (deleteOwnedChildWrites.isEmpty()) return emptyMap()
        val columns = deleteOwnedChildWrites.map { it.parentFkColumn }

        val rows =
            executor.executeAndGetAll(
                dsl.select(listOf(idColumn) + columns).from(table).where(condition),
            )
        return rows.associate { row ->
            row.rawOrNull(idColumn.name) to
                deleteOwnedChildWrites.associateWith { row.rawOrNull(it.parentFkColumn.name) }
        }
    }

    /**
     * [readCascadeRootValues]'s multi-row counterpart - one `SELECT id, parentRootColumn... FROM
     * table WHERE [condition]` for every id at once, keyed by each matched row's own raw id.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun readCascadeRootValuesBatch(
        executor: QueryExecutor,
        condition: Condition,
    ): Map<Any?, Map<TableField<*, *>, Any?>> {
        val columns =
            (
                deleteOneToManyWrites.map { it.parentRootColumn } +
                    deleteJunctionWrites.map { it.parentRootColumn }
            ).distinct()
        if (columns.isEmpty()) return emptyMap()
        val rows =
            executor.executeAndGetAll(
                dsl.select(listOf(idColumn) + columns).from(table).where(condition),
            )
        return rows.associate { row ->
            row.rawOrNull(idColumn.name) to columns.associateWith { row.rawOrNull(it.name) }
        }
    }

    /**
     * Tries [deleteAllOptimized] - one bulk cascade read, batched junction/one-to-many cascade
     * removal, one batched `DELETE`/soft-`UPDATE` `WHERE id IN (...)` - and, for every input whose
     * id didn't come back matched (wrong id, stale version), falls back to a single [delete] call
     * to get the precise exception; a match is never redone. Only a structural failure (the
     * optimized attempt throwing before producing *any* result) falls back *every* item to
     * [delete].
     */
    override suspend fun deleteAll(items: List<DeleteBatchItem<ID>>): BatchResult<Unit> {
        if (items.isEmpty()) return BatchResult(emptyList())
        return runCatchingNonCancellation { deleteAllOptimized(items) }
            .fold(
                onSuccess = { matchedRawIds ->
                    BatchResult(
                        items.map { item ->
                            if (mapId(item.id) in matchedRawIds) {
                                BatchItemResult.Success(Unit)
                            } else {
                                itemResultOf { delete(item.id, item.expectedVersion) }
                            }
                        },
                    )
                },
                onFailure = {
                    BatchResult(items.map { itemResultOf { delete(it.id, it.expectedVersion) } })
                },
            )
    }
}
