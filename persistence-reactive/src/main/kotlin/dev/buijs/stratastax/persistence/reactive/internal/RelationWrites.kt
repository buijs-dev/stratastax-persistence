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

import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.reactive.spi.JunctionWrite
import dev.buijs.stratastax.persistence.reactive.spi.OneToManyCreateWrite
import dev.buijs.stratastax.persistence.reactive.spi.OneToManyUpdateWrite
import org.jooq.TableField
import org.jooq.impl.DSL

/** A command paired with the key of the row it wrote, the parent of its relations. */
internal class Parent<C>(
    val command: C,
    val row: RowContext,
) {

    /** The value of [column] returned by the write, see [returnedAlias]. */
    fun value(column: TableField<*, *>): Any =
        row.rawOrNull(returnedAlias(column))
            ?: throw IllegalStateException("Column '${column.name}' of the written row is null")
}

/**
 * Writes the links of [junctions] for [parents]: one target lookup and one statement per junction
 * for all parents. With [replace], the existing links of each parent whose command names its
 * targets are synchronized: links no longer wanted are deleted, missing ones inserted.
 */
internal suspend fun <C> RowWriter.writeJunctions(
    junctions: List<JunctionWrite<C>>,
    parents: List<Parent<C>>,
    replace: Boolean,
) {
    junctions.forEach { junction ->
        val wanted =
            parents.mapNotNull { parent ->
                junction.mapper.targetIds(parent.command)?.let {
                    parent.value(junction.parentRootColumn) to
                        it
                }
            }

        if (wanted.isEmpty()) return@forEach
        val resolved =
            resolveJunctionTargets(junction, wanted.flatMapTo(mutableSetOf()) { it.second })
        val desired = wanted.flatMap { (root, ids) -> ids.map { root to resolved[it] } }.toSet()
        val root = junction.rootColumn
        val target = junction.targetColumn
        val existing =
            if (!replace) {
                emptySet()
            } else {
                executor
                    .executeAndGetAll(
                        dsl
                            .select(root.`as`("stx_root"), target.`as`("stx_target"))
                            .from(junction.table)
                            .where(root.isOneOf(wanted.map { it.first })),
                    ).map { it.rawOrNull("stx_root") to it.rawOrNull("stx_target") }
                    .toSet()
            }

        val obsolete = existing - desired
        obsolete.chunked(rowsPerStatement(2)).forEach { chunk ->
            val pairs = chunk.map { (rootValue, targetValue) -> DSL.row(rootValue, targetValue) }

            executor.executeUpdate(
                dsl
                    .deleteFrom(
                        junction.table,
                    ).where(DSL.row(root.untyped(), target.untyped()).`in`(pairs)),
            )
        }

        insertRows(
            junction.table,
            (desired - existing).map { (rootValue, targetValue) ->
                mapOf(root to rootValue, target to targetValue)
            },
        )
    }
}

/**
 * Inserts the child rows of [write] for [parents] (command and parent key) - one batch for the
 * children of every parent - and then, recursively, the relations nested in those children.
 */
internal suspend fun <C, E> RowWriter.createChildren(
    write: OneToManyCreateWrite<C, E>,
    parents: List<Pair<C, Any>>,
) {
    val children =
        parents.flatMap { (command, parentKey) ->
            write.elements(command).map {
                it to
                    parentKey
            }
        }

    if (children.isEmpty()) return
    val elements = children.map { it.first }

    val mapped = elements.map { write.elementMapper.map(it).values }

    val foreignKeys = resolveForeignKeys(write.elementFkResolves, elements)
    val owned = createOwnedChildren(write.elementOwnedChildWrites, elements)
    val rows =
        children.indices.map { index ->
            mergeDisjoint(
                mapped[index],
                foreignKeys[index],
                owned[index],
                mapOf(write.parentColumn to children[index].second),
            )
        }

    insertRows(write.childTable, rows)
    if (write.elementWrites.isEmpty()) return
    val key = checkNotNull(write.childIdentityColumn) { "Nested writes need a childIdentityColumn" }

    val nestedParents =
        elements.zip(mapped).map { (element, values) ->
            element to
                (
                    values[key]
                        ?: throw IllegalArgumentException(
                            "A '${write.childTable.name}' element has no value for '${key.name}'",
                        )
                )
        }

    write.elementWrites.forEach { nested -> createChildren(nested, nestedParents) }
}

/**
 * Synchronizes the child rows of [write] for [parents] with their commands' elements, see
 * [OneToManyUpdateWrite]. A parent whose command leaves the relation out (`elements` is `null`) is
 * not touched.
 *
 * Children are matched on their identity among all rows of the parents, soft-deleted ones included:
 * an element matching a soft-deleted row restores it. Only rows that are not soft-deleted are
 * removed.
 */
internal suspend fun <U, E> RowWriter.synchronizeChildren(
    write: OneToManyUpdateWrite<U, E>,
    parents: List<Pair<U, Any>>,
) {
    val shape = write.shape
    val active =
        parents.mapNotNull { (command, parentKey) ->
            write.elements(command)?.let {
                it to
                    parentKey
            }
        }

    if (active.isEmpty()) return
    val parentKeys = active.map { it.second }

    val ofParents = shape.parentColumn.isOneOf(parentKeys)
    val existing = selectExisting(write, ofParents)
    val children =
        active.flatMap { (elements, parentKey) ->
            elements.map { Child(it, write.elementId(it), parentKey) }
        }

    if (write.replaceAll) {
        remove(write, existing.activeIds)
    }

    val stillExisting =
        if (write.replaceAll && shape.softDeleteColumn == null) {
            emptySet()
        } else {
            existing.allIds
        }

    val (matched, added) = children.partition { it.id in stillExisting }

    updateChildren(write, matched)
    insertChildren(write, added)
    if (write.elementWrites.isNotEmpty()) {
        val nestedParents = children.map { it.element to it.id }

        write.elementWrites.forEach { nested -> synchronizeChildren(nested, nestedParents) }
    }

    if (!write.replaceAll) {
        remove(write, existing.activeIds - children.map { it.id }.toSet())
    }
}

private class Child<E>(
    val element: E,
    val id: Any,
    val parentKey: Any,
)

private class ExistingChildren(
    val allIds: Set<Any?>,
    val activeIds: Set<Any?>,
)

private suspend fun RowWriter.selectExisting(
    write: OneToManyUpdateWrite<*, *>,
    ofParents: org.jooq.Condition,
): ExistingChildren {
    val shape = write.shape
    val softDelete =
        shape.softDeleteColumn
            ?: return selectValues(shape.childTable, shape.childIdentityColumn, ofParents).let {
                ExistingChildren(it, it)
            }

    val rows =
        executor.executeAndGetAll(
            dsl
                .select(
                    shape.childIdentityColumn.`as`(KEY_ALIAS),
                    softDelete.isNull.`as`("stx_active"),
                ).from(shape.childTable)
                .where(ofParents),
        )
    return ExistingChildren(
        allIds = rows.map { it.rawOrNull(KEY_ALIAS) }.toSet(),
        activeIds =
            rows
                .filter {
                    it.boolean(
                        "stx_active",
                    )
                }.map { it.rawOrNull(KEY_ALIAS) }
                .toSet(),
    )
}

private suspend fun RowWriter.remove(
    write: OneToManyUpdateWrite<*, *>,
    ids: Set<Any?>,
) {
    if (ids.isEmpty()) return
    val shape = write.shape
    val softDeleted = shape.softDeleteColumn != null
    cascadeRemove(write.elementWrites.map { it.cascade }, ids, parentSoftDeleted = softDeleted)
    val matching = shape.childIdentityColumn.isOneOf(ids)
    val ownedIds = readOwnedChildIds(shape.ownedChildren, shape.childTable, matching)
    removeRows(shape.childTable, shape.softDeleteColumn, matching)
    removeOwnedChildren(ownedIds, parentSoftDeleted = softDeleted)
}

/** Updates the [children] that already have a row, restoring a soft-deleted one. */
private suspend fun <E> RowWriter.updateChildren(
    write: OneToManyUpdateWrite<*, E>,
    children: List<Child<E>>,
) {
    if (children.isEmpty()) return
    val shape = write.shape
    val elements = children.map { it.element }

    val foreignKeys = resolveForeignKeys(write.elementFkResolves, elements)
    val restore: RowValues = shape.softDeleteColumn?.let { mapOf(it to null) } ?: emptyMap()
    val rows =
        children.mapIndexed { index, child ->
            KeyedRow(
                key = child.id,
                expectedVersion = null,
                values =
                    mergeDisjoint(
                        write.elementMapper.map(child.element).values,
                        foreignKeys[index],
                        mapOf(shape.parentColumn to child.parentKey),
                        restore,
                    ),
            )
        }

    val updated = updateRows(shape.childTable, shape.childIdentityColumn, rows)
    check(updated.size == rows.size) {
        "UPDATE of '${shape.childTable.name}' matched ${updated.size} rows, expected ${rows.size}"
    }
}

/** Inserts the [children] that have no row yet, starting them at the initial version. */
private suspend fun <E> RowWriter.insertChildren(
    write: OneToManyUpdateWrite<*, E>,
    children: List<Child<E>>,
) {
    if (children.isEmpty()) return
    val shape = write.shape
    val elements = children.map { it.element }

    val foreignKeys = resolveForeignKeys(write.elementFkResolves, elements)
    val owned = createOwnedChildren(write.elementOwnedChildCreateWrites, elements)
    val version: RowValues =
        write.version?.let { mapOf(it.column to it.initial.initial(it.column)) } ?: emptyMap()
    val rows =
        children.mapIndexed { index, child ->
            mergeDisjoint(
                write.elementMapper.map(child.element).values,
                foreignKeys[index],
                owned[index],
                version,
                mapOf(shape.parentColumn to child.parentKey, shape.childIdentityColumn to child.id),
            )
        }

    insertRows(shape.childTable, rows)
}
