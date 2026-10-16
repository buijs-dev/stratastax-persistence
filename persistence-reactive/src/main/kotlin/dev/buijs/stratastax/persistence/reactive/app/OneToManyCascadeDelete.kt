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

import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * For each of [elementWrites] (a managed direct-FK 1:N group), removes every one of *its* own rows
 * whose [OneToManyUpdateWrite.parentColumn] matches one of [parentIds] - shared by
 * [JooqUpdateAccess] (a parent *element* being removed during an `update()` diff - no single
 * incoming command to diff against, the whole parent row is gone) and [JooqDeleteAccess] (the root
 * parent row itself being deleted). Recurses into a *further*-nested
 * [OneToManyUpdateWrite.elementWrites] first (an extra `SELECT` of the about-to-be-removed rows'
 * own ids, needed only when there's a deeper level to cascade into), so removal always proceeds
 * leaf-first - required for a hard-`DELETE` chain to never trip a live FK constraint from a
 * still-present grandchild row. Type-parameter-free (unlike this function's own `elementWrites`
 * entries, each still generic over its own `U`/`E`) - nothing here ever calls
 * [OneToManyUpdateWrite.elements]/[OneToManyUpdateWrite.elementMapper]/
 * [OneToManyUpdateWrite.elementId], only the shape-describing properties (`childTable`,
 * `parentColumn`, `childIdentityColumn`, `softDeleteColumn`, `elementWrites`,
 * `elementOwnedChildWrites`), so a star-projected `OneToManyUpdateWrite<*, *>` is all this ever
 * needs.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun cascadeRemoveElementWrites(
    dsl: DSLContext,
    executor: QueryExecutor,
    elementWrites: List<OneToManyUpdateWrite<*, *>>,
    parentIds: Set<Any?>,
) {
    if (parentIds.isEmpty()) return
    elementWrites.forEach { write ->
        val parentCol = write.parentColumn as TableField<Record, Any?>
        if (write.elementWrites.isNotEmpty()) {
            val ownIds =
                executor
                    .executeAndGetAll(
                        dsl
                            .select(write.childIdentityColumn)
                            .from(write.childTable)
                            .where(parentCol.`in`(parentIds)),
                    ).mapNotNull { it.rawOrNull(write.childIdentityColumn.name) }
                    .toSet()
            cascadeRemoveElementWrites(dsl, executor, write.elementWrites, ownIds)
        }

        val ownedChildIds =
            readOwnedChildIdsFor(
                dsl,
                executor,
                write.elementOwnedChildWrites,
                write.childTable,
                parentCol.`in`(parentIds),
            )
        val softDeleteCol = write.softDeleteColumn as? TableField<Record, Any?>
        if (softDeleteCol != null) {
            executor.executeUpdate(
                dsl
                    .update(write.childTable)
                    .set(softDeleteCol, DSL.currentOffsetDateTime())
                    .where(parentCol.`in`(parentIds)),
            )
        } else {
            executor.executeUpdate(
                dsl.deleteFrom(write.childTable).where(parentCol.`in`(parentIds)),
            )
        }

        deleteOwnedChildRows(dsl, executor, ownedChildIds)
    }
}

/**
 * For every owned 1:1 group nested inside a managed group's own child (e.g.
 * `MatchParticipant.loadout`), reads the about-to-be-removed rows' own FK value(s) - matched by
 * [matchCondition] against [childTable]. Split from the actual owned-child delete
 * ([deleteOwnedChildRows]) - called *before* those rows are themselves deleted, since a
 * hard-deleted row's own FK value can no longer be read back afterward, while the owned child rows
 * themselves must only be deleted *after* (the row holding the FK *reference* must disappear first,
 * or the *referenced* child row can't be removed without tripping the FK constraint itself).
 * Returns `emptyMap()` (no query at all) when [elementOwnedChildWrites] is empty.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun readOwnedChildIdsFor(
    dsl: DSLContext,
    executor: QueryExecutor,
    elementOwnedChildWrites: List<OwnedChildDeleteWrite>,
    childTable: Table<*>,
    matchCondition: Condition,
): Map<OwnedChildDeleteWrite, Set<Any?>> {
    if (elementOwnedChildWrites.isEmpty()) return emptyMap()
    val fkColumns = elementOwnedChildWrites.map { it.parentFkColumn }

    val rows =
        executor.executeAndGetAll(dsl.select(fkColumns).from(childTable).where(matchCondition))
    return elementOwnedChildWrites.associateWith { ownedWrite ->
        rows.mapNotNull { it.rawOrNull(ownedWrite.parentFkColumn.name) }.toSet()
    }
}

/**
 * Deletes (or soft-deletes) every owned child row identified by [readOwnedChildIdsFor]'s own
 * result - see that function's kdoc for why this must run *after* the rows that held the FK
 * reference are themselves already gone. A no-op entry (empty id set) is skipped with no query at
 * all.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun deleteOwnedChildRows(
    dsl: DSLContext,
    executor: QueryExecutor,
    ownedChildIds: Map<OwnedChildDeleteWrite, Set<Any?>>,
) {
    ownedChildIds.forEach { (ownedWrite, childIds) ->
        if (childIds.isEmpty()) return@forEach
        val childCondition = (ownedWrite.childIdColumn as TableField<Record, Any?>).`in`(childIds)
        ownedWrite.softDeleteColumn?.let { sdCol ->
            executor.executeUpdate(
                dsl
                    .update(ownedWrite.childTable)
                    .set(sdCol as TableField<Record, Any?>, DSL.currentOffsetDateTime() as Any?)
                    .where(childCondition),
            )
        } ?: executor.executeUpdate(dsl.deleteFrom(ownedWrite.childTable).where(childCondition))
    }
}
