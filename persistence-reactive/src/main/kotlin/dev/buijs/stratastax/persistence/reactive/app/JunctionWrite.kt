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

import org.jooq.Table
import org.jooq.TableField

/**
 * Extracts the set of target-side ids a many-to-many `junction<...> { }` relation (see
 * `JooqJunctionBuilder` in search-jooq) should be written from a create/update command, e.g.
 * `CreatePlayset::expansionIds`. `null` means "don't touch this junction's rows at all for this
 * call" - a generated `${Entity}UpdateAccess` produces this for a `PatchField`-typed junction
 * binding left [dev.buijs.stratastax.entity.core.api.PatchField.Unset] (see
 * [dev.buijs.stratastax.entity.core.api.toTargetIdsOrNull]); create-side codegen never produces
 * `null` (a create command always supplies its full initial association set), so [JooqCreateAccess]
 * only guards against it defensively.
 */
fun interface JunctionWriteMapper<C> {

    fun targetIds(command: C): Set<Any?>?
}

/**
 * One junction/link-table relation a [JooqCreateAccess]/[JooqUpdateAccess] writes to, alongside the
 * root row itself - see those interfaces' `createJunctionWrites`/`updateJunctionWrites` properties
 * for how it's used. [table]/[rootColumn]/[targetColumn] mirror exactly what `junction<...> { }`
 * declared in the DSL (e.g. `PLAYSET_EXPANSION`/`PLAYSET_ID`/`EXPANSION_ID`).
 *
 * [parentRootColumn] is the *parent* table's own column the junction relation was declared against
 * (`junction<...> { root(from = X, ...) }`'s `X`, e.g. `PLAYSET.ID`) - **not necessarily the same
 * column as the entity's own `idField`** (e.g. `PLAYSET.PUBLIC_ID`).
 * `JooqCreateAccess`/`JooqUpdateAccess` read this column's freshly-written value back via
 * `RowContext.rawOrNull` (untyped - this column has no Kotlin type known at codegen time, unlike
 * `idField`, which is anchored to a real entity constructor property) and bind it as [rootColumn]'s
 * value for every row this junction writes. A real reported bug: the write path used to always
 * reuse `idField`'s own value for this, which silently broke (`NOT NULL` violation, or a
 * wrong-but-non-null value) whenever `idField`'s column differed from the junction's own root
 * column.
 *
 * [targetResolve], when non-null, resolves [mapper]'s own supplied target ids (a public id, e.g.
 * `EXPANSION.PUBLIC_ID`) to the target table's own raw column value (e.g. `EXPANSION.ID`, what
 * actually gets bound as [targetColumn]'s value) before writing - see [JunctionTargetResolve]'s own
 * kdoc. `null` (the default) means the command already supplies the target table's own raw id
 * directly, needing no lookup - today's original, unchanged behavior.
 */
class JunctionWrite<C>(
    val table: Table<*>,
    val rootColumn: TableField<*, *>,
    val parentRootColumn: TableField<*, *>,
    val targetColumn: TableField<*, *>,
    val mapper: JunctionWriteMapper<C>,
    val targetResolve: JunctionTargetResolve? = null,
)

/**
 * One junction/link-table relation a [JooqDeleteAccess] deletes every row of (matched by
 * [rootColumn]) when the parent row itself is deleted - the delete-side counterpart of
 * [JunctionWrite], carrying only what a plain `DELETE FROM table WHERE rootColumn = ?` needs.
 * Deliberately not [JunctionWrite] itself - that class requires a
 * [JunctionWrite.mapper]/[JunctionWrite.targetColumn] a delete has no use for.
 *
 * By default (`cascadeTarget == null`, from `@PersistenceJoinMany(owned = false)` - the default)
 * only the link rows in the junction's own table are removed - the junction's own *target* table
 * (e.g. `EXPANSION`) is never touched, since a many-to-many junction's target rows are normally
 * shared with other parents too. [cascadeTarget] is set only when the relation was declared `owned
 * = true` - a deliberate, explicit modeling choice that this particular "many" side is exclusively
 * owned by this parent despite being reached via a junction table, in which case the now-unlinked
 * target rows are deleted too (see [JooqDeleteAccess.delete]'s own kdoc for the exact order this
 * runs in).
 *
 * @property parentRootColumn Same caveat as [JunctionWrite.parentRootColumn]: the parent table's
 *   own column the junction relation was declared against - not necessarily the same column as the
 *   entity's own `idField` - so [JooqDeleteAccess] must read this column's current value before
 *   deleting the parent row, never assume it equals the deleted id.
 */
class JunctionDeleteWrite(
    val table: Table<*>,
    val rootColumn: TableField<*, *>,
    val parentRootColumn: TableField<*, *>,
    val cascadeTarget: JunctionCascadeTarget? = null,
)

/**
 * The junction's own target-side info [JunctionDeleteWrite] needs to also cascade-delete the target
 * rows a junction's link rows pointed to, once unlinked - only ever set for an `owned = true`
 * junction relation.
 *
 * @property table The target table itself (e.g. `EXPANSION`).
 * @property targetColumn The target table's own PK column (e.g. `EXPANSION.ID`) matched against the
 *   ids read off [junctionTargetColumn] before the junction's link rows are removed.
 * @property junctionTargetColumn The junction table's own FK-to-target column (e.g.
 *   `PLAYSET_EXPANSION.EXPANSION_ID`) - read *before* the link rows are deleted, since afterward
 *   there'd be nothing left to read it from.
 */
class JunctionCascadeTarget(
    val table: Table<*>,
    val targetColumn: TableField<*, *>,
    val junctionTargetColumn: TableField<*, *>,
)
