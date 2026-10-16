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

import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableField

/**
 * A top-level *owned* 1:1 `@PersistenceJoin(owned = true)` child (see that annotation's own kdoc,
 * search-jooq's `PersistenceAnnotations.kt`) - a genuinely separate, fully-fledged entity (e.g.
 * `LOADOUT`) whose own row is cascade-created alongside the parent that owns it (e.g.
 * `MATCH_PARTICIPANT`), rather than referenced by id. Unlike [OneToManyCreateWrite] (N children,
 * one shared child table, batched, written *after* the parent's own INSERT so they can reference
 * its newly-generated id), this is a single child row, inserted via its own already-generated
 * `${Child}CreateMapper` *before* the parent's own INSERT - the parent's own FK column needs the
 * child's generated id as one of its own `INSERT` values.
 *
 * @property childCommand Extracts the nested child command from the parent's own command, e.g. `{
 *   command -> command.loadout }` for `CreateMatchParticipant(..., loadout: CreateMatchLoadout?)`.
 *   `null` (rather than throwing) means this parent instance has no owned child to create - the
 *   parent's own FK column is then simply left out of its own `INSERT` (i.e. `NULL`, assuming the
 *   column is nullable - a `NOT NULL` FK column with no incoming child command fails at the
 *   database, same as omitting any other required column would).
 * @property childMapper Maps the child command to its own column/value bag - the child entity's own
 *   already-generated `${Child}CreateMapper`, the same bean a hand-written `${Child}CreateAccess`
 *   would inject.
 * @property childTable The child's own table, e.g. `LOADOUT`.
 * @property childIdColumn The child's own internal identity column read back off its `INSERT ...
 *   RETURNING` (e.g. `LOADOUT.ID`, the surrogate key [parentFkColumn] itself points at - *not*
 *   necessarily the same column as the child's own public-facing id).
 * @property parentFkColumn The parent's own FK column pointing at the child (e.g.
 *   `MATCH_PARTICIPANT.LOADOUT_ID`) - set to the child's own [childIdColumn] value once its row is
 *   inserted.
 * @property childFkResolves The child's own 1:1 links to other entities (e.g.
 *   `MATCH_EDIT_PLAYSET_CHANGE.NEW_PLAYSET_ID` from `CreateMatchEditPlaysetChange.newPlaysetId`),
 *   resolved inline in the child's own `INSERT`, exactly like a root's own `createFkResolves`.
 */
class OwnedChildCreateWrite<C, C2>(
    val childCommand: (C) -> C2?,
    val childMapper: CreateMapper<C2, CreateValues>,
    val childTable: Table<*>,
    val childIdColumn: TableField<*, *>,
    val parentFkColumn: TableField<*, *>,
    val childFkResolves: List<GroupFkResolve<C2>> = emptyList(),
)

/**
 * The delete-side counterpart of [OwnedChildCreateWrite] - deletes (or soft-deletes) an owned 1:1
 * child's own row when the parent that owns it is itself deleted, e.g. a `LOADOUT` row disappearing
 * alongside the `MATCH_PARTICIPANT` row that owned it. No command/mapper is needed here (unlike the
 * create side) - deleting an existing row needs only its own id, read off the parent's own current
 * [parentFkColumn] value *before* the parent's own row is deleted (see [JooqDeleteAccess.delete]'s
 * own ordering: the parent's own row - the one holding the FK *reference* - must go first, so
 * deleting the *referenced* child row afterward never trips the FK constraint itself; deleting
 * child-before-parent would).
 *
 * @property childTable The child's own table, e.g. `LOADOUT`.
 * @property childIdColumn The child's own internal identity column - what [parentFkColumn]'s value
 *   is matched against to find its one owned row, e.g. `LOADOUT.ID`.
 * @property parentFkColumn The parent's own FK column pointing at the child, e.g.
 *   `MATCH_PARTICIPANT.LOADOUT_ID` - read (via a `SELECT`, before the parent's own row is touched)
 *   to learn which child row to delete.
 * @property softDeleteColumn The child's own soft-delete timestamp column, when set - mirrors
 *   [JooqDeleteAccess.softDeleteColumn]'s own convention, independently of whichever the *parent*
 *   itself uses. `null` (the default) means a real `DELETE`. A parent configured to *soft*-delete
 *   still leaves its own row (and FK reference) physically in place, so an owned child sharing that
 *   same FK constraint should ordinarily be soft-deleted too, not hard-deleted, to avoid violating
 *   it - a domain modeler's own responsibility to configure consistently, not something this class
 *   can verify at compile or scan time.
 */
class OwnedChildDeleteWrite(
    val childTable: Table<*>,
    val childIdColumn: TableField<*, *>,
    val parentFkColumn: TableField<*, *>,
    val softDeleteColumn: TableField<*, *>? = null,
)

/**
 * Inserts [write]'s own owned child row for [command] (via its own `${Child}CreateMapper`/`INSERT
 * ... RETURNING`) and returns its newly generated id, keyed by
 * [OwnedChildCreateWrite.parentFkColumn] - `null` when [command] has no owned child command at all
 * (see [OwnedChildCreateWrite.childCommand]'s own kdoc).
 *
 * Generic over both [O] (the owning command type) and [C2] (the child's own command type) - **not**
 * tied to any particular access interface - so this one function serves every owned-child-create
 * call site in this module: [JooqCreateAccess]'s own root-level (`O` = that interface's own `C`)
 * and nested-in-managed-group-element (`O` = that element's own type) cases, and
 * [JooqUpdateAccess]'s own nested-in-managed-group-element case for an element being freshly
 * inserted during `update()` (every element under [OneToManyUpdateWrite.replaceAll], or just a
 * newly-added one under the ordinary diff-based upsert) - exactly the same "generic over the
 * caller's own type parameter" shape [OneToManyCreateWrite]/[OneToManyUpdateWrite]'s own recursive
 * `elementWrites` handling already uses.
 */
internal suspend fun <O, C2> writeOwnedChildRow(
    dsl: DSLContext,
    executor: QueryExecutor,
    write: OwnedChildCreateWrite<O, C2>,
    command: O,
): Pair<Field<*>, Any?>? {
    val childCommand = write.childCommand(command) ?: return null
    val childValues = write.childMapper.map(childCommand).values
    val query =
        dsl.buildCreateQuery(
            write.childTable,
            childValues,
            write.childFkResolves,
            childCommand,
            write.childIdColumn,
        )
    val insertedRow =
        executor.executeReturning(query)
            ?: run {
                // A missing referenced row collapses the INSERT to zero rows.
                dsl.throwOnMissingFkReference(executor, write.childFkResolves, childCommand)
                error(
                    "owned child INSERT ... RETURNING produced no row for " +
                        "'${write.childTable.name}' - this should be impossible on a successful insert",
                )
            }

    val childId =
        insertedRow.rawOrNull(write.childIdColumn.name)
            ?: error(
                "owned child id column '${write.childIdColumn.name}' was not " +
                    "returned by INSERT ... RETURNING - this should be impossible",
            )
    return write.parentFkColumn to childId
}
