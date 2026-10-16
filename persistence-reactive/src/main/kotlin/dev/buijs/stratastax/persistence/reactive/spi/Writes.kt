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
package dev.buijs.stratastax.persistence.reactive.spi

import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.core.spi.UpdateMapper
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableField

/** The column values one created row is inserted with, e.g. `CreateValues(GAME.NAME to "Catan")`. */
public class CreateValues(
    vararg entries: Pair<Field<*>, Any?>,
) {

    /** The values by column. */
    public val values: Map<Field<*>, Any?> = entries.toList().toDistinctColumnMap()
}

/**
 * The column values one update writes. A column that is absent is left untouched, which is how a
 * `PatchField.Unset` command property is expressed (see [toUpdateEntry]).
 */
public class UpdateValues(
    entries: List<Pair<Field<*>, Any?>>,
) {

    public constructor(vararg entries: Pair<Field<*>, Any?>) : this(entries.toList())

    /** The values by column. */
    public val values: Map<Field<*>, Any?> = entries.toDistinctColumnMap()
}

private fun List<Pair<Field<*>, Any?>>.toDistinctColumnMap(): Map<Field<*>, Any?> {
    val columns = map { it.first }

    val duplicates = columns.groupBy { it }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "Columns given more than once: ${duplicates.map { it.name }}" }

    return toMap()
}

/**
 * A one-to-one relation a command references by the related entity's public id: [valueSupplier]
 * reads that id (a value of [identityColumn], e.g. `GAME.PUBLIC_ID`) off the command, and the
 * write stores the matching [targetColumn] value (e.g. `GAME.ID`) in [fkColumn] (e.g.
 * `PLAYSET.GAME_ID`).
 *
 * - [valueSupplier] returning `null` sets [fkColumn] to `NULL` (no relation).
 * - [skip] returning `true` leaves [fkColumn] untouched (an update that does not mention the
 *   relation, i.e. a `PatchField.Unset` property).
 * - An id with no matching row fails the write with
 *   [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException].
 */
public class GroupFkResolve<C>(
    public val fkColumn: TableField<*, *>,
    public val identityColumn: TableField<*, *>,
    public val targetColumn: TableField<*, *>,
    public val valueSupplier: (C) -> Any?,
    public val skip: (C) -> Boolean = { false },
)

/** Reads the target ids of a many-to-many relation off a command; `null` leaves the links as is. */
public fun interface JunctionWriteMapper<C> {

    public fun targetIds(command: C): Set<Any?>?
}

/**
 * The links of a many-to-many relation written with the root row: one row in [table] per target
 * id, holding [parentRootColumn]'s value (the root row's key, e.g. `PLAYSET.ID`) in [rootColumn]
 * and the target id in [targetColumn]. An update replaces the links with the ones of the command.
 *
 * @property targetResolve resolves public target ids to the target table's key first; `null` when
 *   the command already holds the key.
 */
public class JunctionWrite<C>(
    public val table: Table<*>,
    public val rootColumn: TableField<*, *>,
    public val parentRootColumn: TableField<*, *>,
    public val targetColumn: TableField<*, *>,
    public val mapper: JunctionWriteMapper<C>,
    public val targetResolve: JunctionTargetResolve? = null,
)

/**
 * Resolves public target ids (values of [identityColumn], e.g. `EXPANSION.PUBLIC_ID`) to the target
 * table's key ([rawColumn], e.g. `EXPANSION.ID`) in one query; an id without a match fails the write
 * with [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException].
 */
public class JunctionTargetResolve(
    public val identityColumn: TableField<*, *>,
    public val rawColumn: TableField<*, *>,
)

/**
 * The links of a many-to-many relation removed when the root row is hard-deleted. A soft-deleted
 * root keeps its links, so it can be restored.
 *
 * @property cascadeTarget also deletes the linked target rows (an owned relation).
 */
public class JunctionDeleteWrite(
    public val table: Table<*>,
    public val rootColumn: TableField<*, *>,
    public val parentRootColumn: TableField<*, *>,
    public val cascadeTarget: JunctionCascadeTarget? = null,
)

/** The target rows a [JunctionDeleteWrite] deletes: rows of [table] where [targetColumn] is linked. */
public class JunctionCascadeTarget(
    public val table: Table<*>,
    public val targetColumn: TableField<*, *>,
    public val junctionTargetColumn: TableField<*, *>,
)

/**
 * The child rows of a one-to-many relation inserted with a new root row: one row in [childTable]
 * per element of [elements], mapped by [elementMapper], with [parentColumn] set to the root's
 * [parentRootColumn] value.
 *
 * @property elementFkResolves one-to-one relations of an element, referenced by public id.
 * @property childIdentityColumn the element's own key, read from its mapped values; required when
 *   [elementWrites] is not empty, as the key the nested rows reference.
 * @property elementWrites one-to-many relations nested in the element.
 * @property elementOwnedChildWrites owned one-to-one rows of the element, inserted before it.
 */
public class OneToManyCreateWrite<C, E>(
    public val childTable: Table<*>,
    public val parentColumn: TableField<*, *>,
    public val parentRootColumn: TableField<*, *>,
    public val elements: (C) -> Collection<E>,
    public val elementMapper: CreateMapper<E, CreateValues>,
    public val elementFkResolves: List<GroupFkResolve<E>> = emptyList(),
    public val childIdentityColumn: TableField<*, *>? = null,
    public val elementWrites: List<OneToManyCreateWrite<E, *>> = emptyList(),
    public val elementOwnedChildWrites: List<OwnedChildCreateWrite<E, *>> = emptyList(),
) {

    init {
        require(elementWrites.isEmpty() || childIdentityColumn != null) {
            "OneToManyCreateWrite on '${childTable.name}' has nested writes but no childIdentityColumn"
        }
    }
}

/**
 * The tables and columns of a one-to-many relation - all that is needed to remove its child rows.
 *
 * @property childIdentityColumn the child's own key.
 * @property softDeleteColumn when set, a child removed by an update, or with a soft-deleted parent,
 *   gets this timestamp instead of being deleted; children where it is set count as removed. A
 *   child of a parent that is deleted outright is always deleted with it.
 * @property ownedChildren owned one-to-one rows of a child, removed with it.
 */
public class OneToManyShape(
    public val childTable: Table<*>,
    public val parentColumn: TableField<*, *>,
    public val parentRootColumn: TableField<*, *>,
    public val childIdentityColumn: TableField<*, *>,
    public val softDeleteColumn: TableField<*, *>? = null,
    public val ownedChildren: List<OwnedChildDeleteWrite> = emptyList(),
)

/** A one-to-many relation removed with its parent, including the relations nested in it. */
public class OneToManyCascade(
    public val shape: OneToManyShape,
    public val nested: List<OneToManyCascade> = emptyList(),
)

/**
 * The child rows of a one-to-many relation synchronized by an update with the command's
 * [elements], matched on [OneToManyShape.childIdentityColumn] through [elementId]: a matching child
 * is updated, a new one inserted, and a child that is no longer in the command removed (soft or
 * hard, see [OneToManyShape.softDeleteColumn]).
 *
 * @property elements the command's elements; `null` (e.g. a `PatchField.Unset` property) leaves the
 *   children untouched, an empty collection removes them all.
 * @property replaceAll removes every existing child and inserts all elements as new rows, instead
 *   of matching them. The removal goes first, so new rows may repeat a natural key of old ones.
 * @property version the version column of a child row, and the version an inserted one starts at.
 */
public class OneToManyUpdateWrite<U, E>(
    public val shape: OneToManyShape,
    public val elements: (U) -> Collection<E>?,
    public val elementId: (E) -> Any,
    public val elementMapper: UpdateMapper<E, UpdateValues>,
    public val elementFkResolves: List<GroupFkResolve<E>> = emptyList(),
    public val elementWrites: List<OneToManyUpdateWrite<E, *>> = emptyList(),
    public val replaceAll: Boolean = false,
    public val elementOwnedChildCreateWrites: List<OwnedChildCreateWrite<E, *>> = emptyList(),
    public val version: ChildVersion? = null,
) {

    /** The removal of this relation, including its nested relations. */
    public val cascade: OneToManyCascade
        get() = OneToManyCascade(shape, elementWrites.map { it.cascade })
}

/**
 * An owned one-to-one row inserted before its parent: [childCommand] reads it off the parent's
 * command (`null`: no child), and its new key ([childIdColumn]) is stored in the parent's
 * [parentFkColumn].
 */
public class OwnedChildCreateWrite<C, C2>(
    public val childCommand: (C) -> C2?,
    public val childMapper: CreateMapper<C2, CreateValues>,
    public val childTable: Table<*>,
    public val childIdColumn: TableField<*, *>,
    public val parentFkColumn: TableField<*, *>,
    public val childFkResolves: List<GroupFkResolve<C2>> = emptyList(),
)

/**
 * An owned one-to-one row removed after its parent: the row of [childTable] whose [childIdColumn]
 * equals the parent's [parentFkColumn].
 *
 * When the parent is soft-deleted the child is soft-deleted too if it has a [softDeleteColumn], and
 * otherwise kept (the parent still references it).
 */
public class OwnedChildDeleteWrite(
    public val childTable: Table<*>,
    public val childIdColumn: TableField<*, *>,
    public val parentFkColumn: TableField<*, *>,
    public val softDeleteColumn: TableField<*, *>? = null,
)
