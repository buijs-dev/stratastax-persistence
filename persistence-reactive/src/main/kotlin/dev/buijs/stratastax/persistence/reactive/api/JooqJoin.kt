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

import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table

/**
 * A top-level `JOIN` needed to select a 1:1 group's columns directly.
 *
 * @property left `true` renders a `LEFT JOIN` instead of a plain `JOIN` - required whenever the
 *   relation's own FK column is nullable (see `JooqSearchQueryTranslator.renderGroups`'s use of
 *   `from.dataType.nullable()`), otherwise a `null` relation drops the *entire* row from the result
 *   instead of just leaving that relation's own columns `null`.
 */
data class JooqJoin(
    val table: Table<*>,
    val condition: Condition,
    val left: Boolean = false,
)

/**
 * Applies every top-level [JooqSearchParts.joins] entry to a `.from(...)` step, e.g.
 * `dsl.select(parts.selectFields).from(MATCH).applyJoins(parts.joins).where(parts.condition)`.
 */
fun SelectJoinStep<Record>.applyJoins(joins: List<JooqJoin>): SelectJoinStep<Record> =
    joins.fold(this) { step, join -> step.applyJoin(join) }

/**
 * Applies a single [JooqJoin], picking `LEFT JOIN`/`JOIN` per [JooqJoin.left]
 * - shared by [applyJoins] (the top-level join list) and `JooqSearchQueryTranslator.renderGroups`'s
 *   own inline fold for joins nested inside a 1:N group's `MULTISET` subquery, so join-type
 *   handling stays consistent wherever a [JooqJoin] is ever applied.
 */
internal fun <R : Record> SelectJoinStep<R>.applyJoin(join: JooqJoin): SelectJoinStep<R> =
    if (join.left) leftJoin(join.table).on(join.condition) else join(join.table).on(join.condition)
