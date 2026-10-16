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

import dev.buijs.stratastax.persistence.core.api.EntityNotFoundException
import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
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
 * The alias a [GroupFkResolve] entry's derived table selects its single resolved column under, so
 * it can be referenced back out of that table by a fixed, known name regardless of
 * [GroupFkResolve.targetColumn]'s own raw name/casing.
 */
internal const val FK_RESOLVE_ALIAS = "resolved"

/**
 * The alias an extra `RETURNING` column selects a junction's own
 * [dev.buijs.stratastax.persistence.reactive.app.JunctionWrite.parentRootColumn] under, so
 * `JooqCreateAccess`/`JooqUpdateAccess` can read it back via `RowContext.rawOrNull` by a fixed,
 * known name regardless of the column's own raw name/casing - double-underscore-prefixed so it can
 * never collide with an `EntityField.name` (always a plain camelCase domain name).
 */
internal fun junctionRootAlias(column: TableField<*, *>): String = "__junctionRoot_${column.name}"

/**
 * One [GroupFkResolve] entry paired with the alias its derived table will render under in the
 * enclosing statement (stable and unique only within one statement) and the identity value read off
 * the command via [GroupFkResolve.valueSupplier].
 */
private class FkResolveEntry<C>(
    val resolve: GroupFkResolve<C>,
    val alias: String,
    val lookupValue: Any?,
)

private fun <C> List<GroupFkResolve<C>>.withAliases(command: C): List<FkResolveEntry<C>> =
    mapIndexed { index, resolve ->
        FkResolveEntry(resolve, "fk_resolve_$index", resolve.valueSupplier(command))
    }

/**
 * [entry]'s derived table - `(SELECT [GroupFkResolve.targetColumn] AS "resolved" FROM
 * <identityColumn's table> WHERE [GroupFkResolve.identityColumn] = [FkResolveEntry.lookupValue])
 * <alias>` - meant to be joined into the enclosing `INSERT ... SELECT ... FROM`/`UPDATE ... SET ...
 * FROM` statement so the lookup runs inline, in the same round trip as the write, instead of as a
 * separate `SELECT` beforehand. A missing target row means this table contributes zero rows;
 * cross-joined with the rest of the statement's `FROM`, that collapses the *entire* statement to
 * zero affected/inserted rows rather than a DB constraint-violation error - the same "nothing
 * happened" signal an ordinary `RETURNING` already produces on failure, so the surrounding
 * transaction stays healthy and [throwOnMissingFkReference] can safely diagnose that afterward,
 * only in that rare case.
 */
@Suppress("UNCHECKED_CAST")
private fun <C> DSLContext.derivedTable(entry: FkResolveEntry<C>): Table<*> {
    val resolve = entry.resolve
    val identityTable =
        requireNotNull(resolve.identityColumn.table) {
            "GroupFkResolve: identityColumn '${resolve.identityColumn.name}' has no owning table"
        }

    val condition = (resolve.identityColumn as TableField<Record, Any?>).eq(entry.lookupValue)
    return select(resolve.targetColumn.`as`(FK_RESOLVE_ALIAS))
        .from(identityTable)
        .where(condition)
        .asTable(entry.alias)
}

/** [entry]'s resolved value, as a typed [Field] reference into [derived]. */
private fun <C> resolvedField(
    entry: FkResolveEntry<C>,
    derived: Table<*>,
): Field<*> =
    requireNotNull(derived.field(FK_RESOLVE_ALIAS, entry.resolve.targetColumn.dataType)) {
        "derived table '${entry.alias}' has no '$FK_RESOLVE_ALIAS' column - this should be impossible"
    }

/**
 * Builds the `INSERT` for `JooqCreateAccess.create()`. [returningId] - already aliased to the
 * entity's `EntityField.name` by the caller (see `JooqCreateAccess.create`) - plus [extraReturning]
 * (already aliased via [junctionRootAlias] by the caller, one per distinct
 * `JunctionWrite.parentRootColumn` the entity has, if any) are the *only* columns the `RETURNING`
 * clause selects, rather than a bare `RETURNING *`: `extractRootId`/the junction-write path are the
 * only things that ever read the `INSERT ... RETURNING`/`UPDATE ... RETURNING` row's data
 * (everything else the caller returns comes from a proper re-select, see `JooqSelectSupport`), and
 * a bare `RETURNING *`'s labels are the table's raw physical column names - not the DSL field's own
 * name - which can silently collide (e.g. a field named `"id"` bound to `PUBLIC_ID` vs. a genuinely
 * different physical column literally named `id`, the surrogate PK). Selecting and aliasing exactly
 * the columns actually read removes that ambiguity entirely, using the same "alias every selected
 * column as its own name" convention `JooqSearchQueryTranslator` already uses everywhere else - no
 * separate round trip either way. When non-empty, builds `INSERT INTO table (plainCols...,
 * fkCols...) SELECT plainVals..., fk_0.resolved, ... FROM (..) fk_0, (..) fk_1, ... RETURNING
 * <returningId>, <extraReturning...>` - one statement, one round trip; a missing FK reference makes
 * the whole `SELECT` (and therefore the `INSERT`) produce zero rows instead of a DB error.
 *
 * A [GroupFkResolve] whose own [GroupFkResolve.valueSupplier] returns `null` for [command] (an
 * optional 1:1 relation the caller simply didn't provide - see that property's own kdoc) never gets
 * a derived-table join at all: its [GroupFkResolve.fkColumn] is bound directly to `NULL` instead.
 * Joining it anyway (`identityColumn = NULL`/`identityColumn IS NULL`) would either match no row on
 * a non-nullable identity column - collapsing the *entire* `INSERT` to zero rows, wrongly treated
 * as a missing reference - or, worse, match every row whose identity column happens to itself be
 * `NULL`; neither is "this relation wasn't provided." A [GroupFkResolve] whose
 * [GroupFkResolve.skip] returns `true` is excluded even earlier, before this null-check - it never
 * reaches [GroupFkResolve.fkColumn] at all, `NULL` or otherwise (irrelevant on `create()`, since
 * there's no existing row value to preserve, but shared with [buildUpdateQuery] below, where it
 * matters).
 */
@Suppress("UNCHECKED_CAST")
internal fun <C> DSLContext.buildCreateQuery(
    table: Table<*>,
    plainValues: Map<Field<*>, Any?>,
    resolves: List<GroupFkResolve<C>>,
    command: C,
    returningId: Field<*>,
    extraReturning: List<Field<*>> = emptyList(),
): Query {
    val returningFields = listOf(returningId) + extraReturning
    val active = resolves.filterNot { it.skip(command) }

    val (unresolved, resolvable) = active.partition { it.valueSupplier(command) == null }

    val unresolvedFkValues: Map<Field<*>, Any?> = unresolved.associate { it.fkColumn to null }

    val allPlainValues = plainValues + unresolvedFkValues
    if (resolvable.isEmpty()) {
        return insertInto(table).set(allPlainValues).returning(returningFields)
    }

    val entries = resolvable.withAliases(command)
    val derived = entries.map { it to derivedTable(it) }

    val plainColumns = allPlainValues.keys.toList()
    val plainItems: List<Field<*>> =
        plainColumns.map { field ->
            DSL.`val`(allPlainValues[field], field.dataType as DataType<Any?>)
        }

    val fkColumns: List<Field<*>> = entries.map { it.resolve.fkColumn }

    val fkItems: List<Field<*>> =
        derived.map { (entry, derivedTable) -> resolvedField(entry, derivedTable) }

    val selectQuery = select(plainItems + fkItems).from(derived.map { it.second })
    return insertInto(table)
        .columns(plainColumns + fkColumns)
        .select(selectQuery)
        .returning(returningFields)
}

/**
 * Builds the `UPDATE` for `JooqUpdateAccess.update()`. [plainSetValues] already includes any
 * version-bump entry. [returningId] is, like [buildCreateQuery]'s own, the *only* column
 * `RETURNING` selects - `update()` doesn't currently read any data off this row (only null-checks
 * it), but it's kept consistent with `buildCreateQuery` for the same reason: a bare `RETURNING *`'s
 * labels are raw physical column names, not `EntityField` names, which is a latent trap for any
 * future code that *does* read this row. When [resolves] is empty this is exactly
 * `update(table).set(plainSetValues).where(condition).returning(returningId)` - no extra round
 * trip. When non-empty, adds `UPDATE table SET ..., fkCol = fk_0.resolved FROM (..) fk_0, ... WHERE
 * condition RETURNING <returningId>` - a missing FK reference collapses the `FROM` to zero rows, so
 * the `UPDATE` matches/affects zero rows instead of erroring.
 *
 * Same `null`-tolerant treatment as [buildCreateQuery] for a [GroupFkResolve] whose
 * [GroupFkResolve.valueSupplier] returns `null` - its [GroupFkResolve.fkColumn] is set to `NULL`
 * directly, no derived-table join. A [GroupFkResolve] whose [GroupFkResolve.skip] returns `true`
 * for [command] is excluded from [plainSetValues]/the `SET` clause entirely instead - unlike the
 * `null` case above, its column is left untouched, not nulled. This is the genuinely different
 * outcome a `PatchField.Unset`-bound relation needs (patch semantics: "the caller didn't mention
 * this field") that a plain `null` return from [GroupFkResolve.valueSupplier] can't express on its
 * own.
 */
internal fun <C> DSLContext.buildUpdateQuery(
    table: Table<*>,
    plainSetValues: Map<Field<*>, Any?>,
    condition: Condition,
    resolves: List<GroupFkResolve<C>>,
    command: C,
    returningId: Field<*>,
    extraReturning: List<Field<*>> = emptyList(),
): Query {
    val returningFields = listOf(returningId) + extraReturning
    val active = resolves.filterNot { it.skip(command) }

    val (unresolved, resolvable) = active.partition { it.valueSupplier(command) == null }

    val unresolvedFkValues: Map<Field<*>, Any?> = unresolved.associate { it.fkColumn to null }

    val allSetValues = plainSetValues + unresolvedFkValues
    if (resolvable.isEmpty()) {
        return update(table).set(allSetValues).where(condition).returning(returningFields)
    }

    val entries = resolvable.withAliases(command)
    val derived = entries.map { it to derivedTable(it) }

    val fkSetValues: Map<Field<*>, Any?> =
        derived.associate { (entry, derivedTable) ->
            entry.resolve.fkColumn to resolvedField(entry, derivedTable)
        }

    return update(table)
        .set(allSetValues + fkSetValues)
        .from(derived.map { it.second })
        .where(condition)
        .returning(returningFields)
}

/**
 * Runs one cheap `SELECT 1 ... WHERE identityColumn = value` existence check per [resolves] entry
 * via [executor]/[QueryExecutor.exists] - only ever called on the rare "write returned zero rows"
 * branch, once the transaction is already known to still be healthy (an empty derived-table join is
 * not a DB error) - and throws [ReferencedEntityNotFoundException] for the first entry whose target
 * row doesn't exist. Returns normally if none are missing, so the caller can attribute the zero-row
 * result to its original cause (an "impossible" internal error for `create()`, a stale id/version
 * for `update()`).
 *
 * A [resolve] whose own [GroupFkResolve.valueSupplier] returns `null` for [command] was never
 * joined at all (see [buildCreateQuery]/[buildUpdateQuery] own kdoc) and so can't be the cause of a
 * zero-row result - skipped.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun <C> DSLContext.throwOnMissingFkReference(
    executor: QueryExecutor,
    resolves: List<GroupFkResolve<C>>,
    command: C,
) {
    resolves.forEach { resolve ->
        if (resolve.skip(command)) return@forEach
        val lookupValue = resolve.valueSupplier(command) ?: return@forEach
        val identityTable =
            requireNotNull(resolve.identityColumn.table) {
                "GroupFkResolve: identityColumn '${resolve.identityColumn.name}' has no owning table"
            }

        val condition = (resolve.identityColumn as TableField<Record, Any?>).eq(lookupValue)
        val found = executor.exists(selectOne().from(identityTable).where(condition))
        if (!found) {
            throw ReferencedEntityNotFoundException(
                identityTable.name,
                resolve.identityColumn.name,
                lookupValue,
            )
        }
    }
}

/**
 * Determines *why* a write's own zero-affected-rows result happened and throws the correct
 * exception - [EntityNotFoundException] when [id] itself has no matching row at all,
 * [OptimisticLockException] when it does but [versionColumn]'s current value didn't match
 * [expectedVersion]. Called only on the already-exceptional "write matched zero rows" branch - in
 * `JooqUpdateAccess.update()`, right after [throwOnMissingFkReference] has already run and didn't
 * throw (ruling out a missing FK reference as the cause); in `JooqDeleteAccess.delete()`, there's
 * no FK write to check, so this is the only diagnosis step.
 *
 * When [versionColumn] is `null`, the `WHERE` clause that produced zero rows was already just `id =
 * ?` alone - no second query needed, zero rows can only mean "no such id". When [versionColumn] is
 * set, zero rows is ambiguous between "no such id" and "stale version" on its own, so one extra
 * `SELECT 1 ... WHERE id = ?` (via [QueryExecutor.exists], the exact idiom
 * [throwOnMissingFkReference] already uses) resolves it - paid only on this already-exceptional
 * path, never on a successful write.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun DSLContext.throwNotFoundOrStaleVersion(
    executor: QueryExecutor,
    table: Table<*>,
    idColumn: TableField<*, *>,
    versionColumn: TableField<*, *>?,
    id: Any,
    expectedVersion: Any?,
): Nothing {
    if (versionColumn == null) {
        throw EntityNotFoundException(table.name, id)
    }

    val condition = (idColumn as TableField<Record, Any?>).eq(id)
    val exists = executor.exists(selectOne().from(table).where(condition))
    if (!exists) {
        throw EntityNotFoundException(table.name, id)
    }

    throw OptimisticLockException(table.name, id, expectedVersion)
}
