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
package dev.buijs.stratastax.persistence.reactive.internal

import dev.buijs.stratastax.persistence.reactive.spi.OneToManyCascade
import dev.buijs.stratastax.persistence.reactive.spi.OwnedChildDeleteWrite
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Removes the rows of [table] matching [condition]: with a [softDeleteColumn] by setting it to the
 * current time (only on rows where it is not set yet - an already removed row stays as it was),
 * otherwise with a `DELETE`. Returns the number of rows removed.
 */
internal suspend fun RowWriter.removeRows(
    table: Table<*>,
    softDeleteColumn: TableField<*, *>?,
    condition: Condition,
): Int =
    if (softDeleteColumn != null) {
        executor.executeUpdate(
            dsl
                .update(table)
                .set(mapOf<Field<*>, Any?>(softDeleteColumn to DSL.currentOffsetDateTime()))
                .where(allOf(condition, activeRows(softDeleteColumn))),
        )
    } else {
        executor.executeUpdate(dsl.deleteFrom(table).where(condition))
    }

/**
 * Removes the child rows of every one of [cascades] that belong to [parentIds], deepest relation
 * first so a delete never trips a foreign key of a row still pointing at it. Owned one-to-one rows
 * of the removed children are removed after them (see [removeOwnedChildren]).
 *
 * The children follow their parent:
 * - a deleted parent ([parentSoftDeleted] `false`) takes all its children with it, also ones that
 *   could be soft-deleted - a foreign key cannot point at a row that is gone;
 * - a soft-deleted parent soft-deletes the children that can be, and keeps the others, so it can be
 *   restored with its children.
 */
internal suspend fun RowWriter.cascadeRemove(
    cascades: List<OneToManyCascade>,
    parentIds: Set<Any?>,
    parentSoftDeleted: Boolean,
) {
    if (parentIds.isEmpty()) return
    cascades.forEach { cascade ->
        val shape = cascade.shape
        if (parentSoftDeleted && shape.softDeleteColumn == null) return@forEach
        val softDelete = if (parentSoftDeleted) shape.softDeleteColumn else null
        val ofParents = allOf(shape.parentColumn.isOneOf(parentIds), activeRows(softDelete))
        if (cascade.nested.isNotEmpty()) {
            val childIds = selectValues(shape.childTable, shape.childIdentityColumn, ofParents)
            cascadeRemove(cascade.nested, childIds, parentSoftDeleted)
        }

        val ownedIds = readOwnedChildIds(shape.ownedChildren, shape.childTable, ofParents)
        removeRows(shape.childTable, softDelete, ofParents)
        removeOwnedChildren(ownedIds, parentSoftDeleted)
    }
}

/**
 * The keys of the owned one-to-one rows the rows of [table] matching [condition] point at, per
 * write. Read before those rows are removed: a hard-deleted row's foreign key cannot be read back.
 */
internal suspend fun RowWriter.readOwnedChildIds(
    writes: List<OwnedChildDeleteWrite>,
    table: Table<*>,
    condition: Condition,
): Map<OwnedChildDeleteWrite, Set<Any?>> {
    if (writes.isEmpty()) return emptyMap()
    val columns = writes.map { it.parentFkColumn }.distinct()
    val rows =
        executor.executeAndGetAll(
            dsl
                .select(
                    columns.map { it.`as`(returnedAlias(it)) },
                ).from(table)
                .where(condition),
        )
    return writes.associateWith { write ->
        rows.mapNotNull { it.rawOrNull(returnedAlias(write.parentFkColumn)) }.toSet()
    }
}

/**
 * Removes the owned one-to-one rows in [childIds], after the rows pointing at them were removed.
 * A child with a soft-delete column is soft-deleted. One without is deleted when its parent was
 * deleted, and kept when its parent was soft-deleted ([parentSoftDeleted]) - the parent still
 * points at it.
 */
internal suspend fun RowWriter.removeOwnedChildren(
    childIds: Map<OwnedChildDeleteWrite, Set<Any?>>,
    parentSoftDeleted: Boolean,
) {
    childIds.forEach { (write, ids) ->
        if (ids.isEmpty()) return@forEach
        if (write.softDeleteColumn == null && parentSoftDeleted) return@forEach
        removeRows(write.childTable, write.softDeleteColumn, write.childIdColumn.isOneOf(ids))
    }
}

/** The values of [column] in the rows of [table] matching [condition]. */
internal suspend fun RowWriter.selectValues(
    table: Table<*>,
    column: Field<*>,
    condition: Condition,
): Set<Any?> =
    executor
        .executeAndGetAll(dsl.select(column.`as`(KEY_ALIAS)).from(table).where(condition))
        .map { it.rawOrNull(KEY_ALIAS) }
        .toSet()
