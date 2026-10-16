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
package dev.buijs.stratastax.persistence.reactive.app

import dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import org.jooq.CaseWhenStep
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.Query
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Resolves [junction]'s own [JunctionWrite.targetResolve] (a public id, e.g. `EXPANSION.PUBLIC_ID`,
 * to the target table's own raw column value, e.g. `EXPANSION.ID`) for every one of [targetIds] at
 * once, in one batched `SELECT ... WHERE identityColumn IN (...)` - the same "one `IN` lookup
 * rather than N single lookups" idiom [resolveElementFkValuesBatch] already uses. Returns raw
 * target id -> resolved value so a caller writing rows for several *parents* at once (a root-level
 * batch `createAll`/`updateAll`) can still look up each parent's own subset of [targetIds]
 * afterward, rather than getting back an unordered [Set] of resolved values with no way to tell
 * which one came from which raw id - unlike a single parent's own junction write, where that
 * correlation doesn't matter (see the `resolveTargetIds` callers in
 * `JooqCreateAccess`/`JooqUpdateAccess`, both of which discard it right back into a plain [Set] for
 * their own single-parent case). `null` [JunctionWrite.targetResolve] means the command already
 * supplies the target table's own raw id directly - every id resolves to itself, no query at all.
 * Throws [ReferencedEntityNotFoundException] for the first supplied id with no matching row,
 * exactly like a single-parent lookup would.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun DSLContext.resolveJunctionTargetIds(
    executor: QueryExecutor,
    junction: JunctionWrite<*>,
    targetIds: Set<Any?>,
): Map<Any?, Any?> {
    val resolve = junction.targetResolve ?: return targetIds.associateWith { it }

    if (targetIds.isEmpty()) return emptyMap()
    val identityTable =
        requireNotNull(resolve.identityColumn.table) {
            "JunctionTargetResolve: identityColumn '${resolve.identityColumn.name}' has no owning table"
        }

    val query =
        select(resolve.rawColumn, resolve.identityColumn)
            .from(identityTable)
            .where((resolve.identityColumn as TableField<Record, Any?>).`in`(targetIds))
    val rows = executor.executeAndGetAll(query)
    val resolved =
        rows.associate {
            it.rawOrNull(resolve.identityColumn.name) to it.rawOrNull(resolve.rawColumn.name)
        }

    val missing = targetIds.firstOrNull { it !in resolved.keys }

    if (missing != null) {
        throw ReferencedEntityNotFoundException(
            identityTable.name,
            resolve.identityColumn.name,
            missing,
        )
    }

    return resolved
}

/**
 * A genuine multi-row `INSERT INTO table (columns...) VALUES (row1...), (row2...), ...` - one round
 * trip for however many [rows] there are, rather than one `INSERT` per row (what
 * [JooqCreateAccess.writeJunctionRows]/ `writeOneToManyElements` and
 * [JooqUpdateAccess.resyncJunctionRows]/ `resyncOneToManyElements` used to do). Each row in [rows]
 * must supply exactly one value per entry in [columns], in the same order - callers build both from
 * the same source list to guarantee this (see
 * [dev.buijs.stratastax.persistence.reactive.api.JooqCreateAccess.writeOneToManyElements]).
 */
internal fun DSLContext.buildBatchInsertQuery(
    table: Table<*>,
    columns: List<Field<*>>,
    rows: List<List<Any?>>,
): Query {
    require(rows.isNotEmpty()) { "buildBatchInsertQuery requires at least one row" }

    var step = insertInto(table, *columns.toTypedArray())
    rows.forEach { row -> step = step.values(row) }

    return step
}

/**
 * [buildBatchInsertQuery]'s `RETURNING` counterpart, for a root-level `createAll` - a plain `INSERT
 * INTO table (columns...) VALUES (row1...), (row2...), ... RETURNING (returningFields...)`, one
 * round trip for every [rows] entry. Unlike a single-row `INSERT ... RETURNING`, the returned rows'
 * own order is correlated back to [rows]' own order **positionally** - relies on Postgres
 * processing/returning a plain `VALUES`-list `INSERT` (no triggers/rules involved) in list order,
 * which isn't part of the SQL standard's own guarantee but is the same assumption
 * bulk-insert-returning-ids code elsewhere already relies on; covered by a real-Postgres
 * persistence-testkit test rather than re-derived here.
 */
internal fun DSLContext.buildBatchInsertReturningQuery(
    table: Table<*>,
    columns: List<Field<*>>,
    rows: List<List<Any?>>,
    returningFields: List<Field<*>>,
): Query {
    require(rows.isNotEmpty()) { "buildBatchInsertReturningQuery requires at least one row" }

    var step = insertInto(table, *columns.toTypedArray())
    rows.forEach { row -> step = step.values(row) }

    return step.returning(returningFields)
}

/**
 * The batch counterpart of [GroupFkResolve]'s inline derived-table resolve (see
 * `FkResolveSupport`'s own kdoc) - rather than one `INSERT ... SELECT ... FROM (derived table)` per
 * element, this runs exactly one `SELECT ... WHERE identityColumn IN (...)` per [resolves] entry
 * (batching every [elements]'s own lookup value into a single `IN` list - the same technique
 * [JooqCreateAccess.resolveTargetIds] already uses for a junction's own target ids), then hands
 * back each element's own resolved `fkColumn -> value` map so the caller can fold it straight into
 * that element's plain column values and build one uniform multi-row
 * [buildBatchInsertQuery]/batch-update statement - no per-row derived table needed. Throws
 * [ReferencedEntityNotFoundException] eagerly, for the first element/resolve pair whose lookup
 * value has no match - the batch INSERT/UPDATE this feeds into has no derived-table "collapse to
 * zero rows" signal to fall back on the way the single-row path does, so this has to be the one
 * place that catches a missing reference.
 *
 * An element whose own [GroupFkResolve.valueSupplier] returns `null` (an optional relation that
 * particular element simply doesn't have) contributes no lookup value and is never looked up at
 * all - its own result maps [GroupFkResolve.fkColumn] straight to `null` instead, exactly like
 * `FkResolveSupport`'s single-row `buildCreateQuery`/`buildUpdateQuery` treat the same case.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun <E> resolveElementFkValuesBatch(
    dsl: DSLContext,
    executor: QueryExecutor,
    resolves: List<GroupFkResolve<E>>,
    elements: List<E>,
): List<Map<Field<*>, Any?>> {
    if (resolves.isEmpty()) return elements.map { emptyMap() }

    val perResolveLookup: List<Map<Any?, Any?>> =
        resolves.map { resolve ->
            val lookupValues = elements.mapNotNull { resolve.valueSupplier(it) }.toSet()
            if (lookupValues.isEmpty()) return@map emptyMap()
            val identityTable =
                requireNotNull(resolve.identityColumn.table) {
                    "GroupFkResolve: identityColumn '${resolve.identityColumn.name}' has no owning table"
                }

            val query =
                dsl
                    .select(resolve.targetColumn, resolve.identityColumn)
                    .from(identityTable)
                    .where((resolve.identityColumn as TableField<Record, Any?>).`in`(lookupValues))
            executor.executeAndGetAll(query).associate {
                it.rawOrNull(resolve.identityColumn.name) to it.rawOrNull(resolve.targetColumn.name)
            }
        }

    return elements.map { element ->
        resolves
            .mapIndexed { index, resolve ->
                val lookupValue = resolve.valueSupplier(element)
                if (lookupValue == null) {
                    return@mapIndexed resolve.fkColumn as Field<*> to null
                }

                val resolved =
                    perResolveLookup[index][lookupValue]
                        ?: throw ReferencedEntityNotFoundException(
                            requireNotNull(resolve.identityColumn.table) {
                                "GroupFkResolve: identityColumn '${resolve.identityColumn.name}' has no owning table"
                            }.name,
                            resolve.identityColumn.name,
                            lookupValue,
                        )
                resolve.fkColumn as Field<*> to resolved
            }.toMap()
    }
}

/**
 * A genuine multi-row `UPDATE table SET col1 = CASE identityColumn WHEN id1 THEN val1a WHEN id2
 * THEN val2a ... END, col2 = CASE ... END WHERE identityColumn IN (id1, id2, ...)` - one round trip
 * for however many [rowsByIdentity] there are, rather than one `UPDATE` per row (what
 * [JooqUpdateAccess.resyncOneToManyElements]'s matched-id branch used to do). [rowsByIdentity] maps
 * each row's own [identityColumn] value to its plain column values (already merged with any
 * resolved FK values - see [resolveElementFkValuesBatch]); every row must carry the same set of
 * [columns] keys (true for a one-to-many group's own element mapper - see
 * `OneToManyWriteEntryRenderer`, which never applies `PatchField` semantics at the element level).
 * No `ELSE` branch is needed - the `WHERE ... IN (...)` clause already restricts the update to
 * exactly the rows with a matching `WHEN` branch, so a row a `CASE` doesn't cover for some column
 * is never touched by this statement in the first place.
 */
@Suppress("UNCHECKED_CAST")
internal fun DSLContext.buildBatchUpdateQuery(
    table: Table<*>,
    identityColumn: TableField<*, *>,
    columns: List<Field<*>>,
    rowsByIdentity: List<Pair<Any?, List<Any?>>>,
): Query {
    require(rowsByIdentity.isNotEmpty()) { "buildBatchUpdateQuery requires at least one row" }

    val identityField = identityColumn as Field<Any?>
    val setValues: Map<Field<*>, Any?> =
        columns
            .mapIndexed { colIndex, column ->
                val dataType = column.dataType as DataType<Any?>
                var caseStep: CaseWhenStep<Any?, Any?>? = null
                rowsByIdentity.forEach { (identityValue, rowValues) ->
                    val literal = DSL.`val`(rowValues[colIndex], dataType)
                    caseStep =
                        caseStep?.`when`(identityValue, literal)
                            ?: DSL.choose(identityField).`when`(identityValue, literal)
                }

                column to requireNotNull(caseStep)
            }.toMap()
    val ids = rowsByIdentity.map { it.first }

    return update(table)
        .set(setValues)
        .where((identityColumn as TableField<Record, Any?>).`in`(ids))
}

/**
 * One [buildBatchUpdateReturningQuery] row - [identityValue]/[values] mean the same as
 * [buildBatchUpdateQuery]'s own `rowsByIdentity` entries; [expectedVersion] is this row's own
 * optimistic-lock check (only used when that query's own `versionColumn` is non-null), independent
 * per row - unlike a single shared `WHERE ... AND version = ?`, a root-level batch update's own
 * rows can each carry a different expected version.
 */
internal class BatchUpdateRow(
    val identityValue: Any?,
    val expectedVersion: Any?,
    val values: List<Any?>,
)

/**
 * [buildBatchUpdateQuery]'s `RETURNING` counterpart, for a root-level `updateAll` - unlike that
 * function, [rows] may carry **different** sets of columns from one call to the next (a caller
 * groups same-shaped rows together first - see `JooqUpdateAccess.updateAllOptimized`'s own kdoc for
 * why a root update command's own column set can vary row to row, unlike a one-to-many element's),
 * but every row passed to *this* one call must share [columns] - the `CASE identityColumn WHEN ...
 * END` structure is still one expression per column, same as [buildBatchUpdateQuery].
 *
 * [versionColumn], when non-null, adds `AND ((identityColumn = id1 AND versionColumn = v1) OR
 * (identityColumn = id2 AND versionColumn = v2) ...)` to the `WHERE` clause, checking every row's
 * own [BatchUpdateRow.expectedVersion] independently - a row whose version doesn't match simply
 * isn't in `RETURNING`'s own result, exactly like a row whose id doesn't exist; the caller tells
 * the two apart afterward with one more query, only for the rows that didn't come back (see
 * `throwNotFoundOrStaleVersion`), the same diagnosis a single-row `update()` already does.
 * [additionalSetValues] folds in whatever else every matched row's own `SET` clause should also
 * carry regardless of [columns] - namely the version bump itself
 * ([dev.buijs.stratastax.persistence.reactive.app.VersionIncrementStrategy]'s own expression),
 * which needs no per-row `CASE` at all since it's the same expression for every row.
 */
@Suppress("UNCHECKED_CAST")
internal fun DSLContext.buildBatchUpdateReturningQuery(
    table: Table<*>,
    identityColumn: TableField<*, *>,
    versionColumn: TableField<*, *>?,
    columns: List<Field<*>>,
    rows: List<BatchUpdateRow>,
    additionalSetValues: Map<Field<*>, Any?>,
    returningFields: List<Field<*>>,
): Query {
    require(rows.isNotEmpty()) { "buildBatchUpdateReturningQuery requires at least one row" }

    val identityField = identityColumn as Field<Any?>
    val caseSetValues: Map<Field<*>, Any?> =
        columns
            .mapIndexed { colIndex, column ->
                val dataType = column.dataType as DataType<Any?>
                var caseStep: CaseWhenStep<Any?, Any?>? = null
                rows.forEach { row ->
                    val literal = DSL.`val`(row.values[colIndex], dataType)
                    caseStep =
                        caseStep?.`when`(row.identityValue, literal)
                            ?: DSL.choose(identityField).`when`(row.identityValue, literal)
                }

                column to requireNotNull(caseStep)
            }.toMap()
    val ids = rows.map { it.identityValue }

    var condition: Condition = (identityColumn as TableField<Record, Any?>).`in`(ids)
    if (versionColumn != null) {
        val versionCol = versionColumn as TableField<Record, Any?>
        val perRowVersionCondition =
            rows
                .map { row ->
                    identityColumn.eq(row.identityValue).and(versionCol.eq(row.expectedVersion))
                }.reduce(Condition::or)
        condition = condition.and(perRowVersionCondition)
    }

    return update(table)
        .set(caseSetValues + additionalSetValues)
        .where(condition)
        .returning(returningFields)
}
