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

import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SelectJoinStep
import org.jooq.SortField
import org.jooq.Table
import org.jooq.impl.DSL

/**
 * A search translated to jOOQ query parts, see [JooqSearchQueryTranslator].
 *
 * @property condition the caller's filter and the default filters: what a count counts.
 * @property orderBy the sort, including the tiebreaker.
 * @property selectFields the select list: aliased root fields, joined one-to-one fields and one
 *   JSON array per one-to-many group.
 * @property joins the joins the select list and the sort need.
 * @property seekCondition the rows past the search's cursor in [orderBy]'s order, if it has a
 *   cursor. Never part of a count.
 */
public data class JooqSearchParts(
    val condition: Condition?,
    val orderBy: List<SortField<*>>,
    val selectFields: List<SelectFieldOrAsterisk>,
    val joins: List<JooqJoin>,
    val seekCondition: Condition? = null,
) {

    /** [condition] and [seekCondition]: the rows of one page, before its limit. */
    val pageCondition: Condition
        get() = DSL.and(listOfNotNull(condition, seekCondition))
}

/**
 * A join needed to select a one-to-one group's columns.
 *
 * @property left renders a `LEFT JOIN` instead of a `JOIN`, for an optional relation.
 */
public data class JooqJoin(
    val table: Table<*>,
    val condition: Condition,
    val left: Boolean = false,
)

/** Applies every one of [joins], in order. */
public fun SelectJoinStep<Record>.applyJoins(joins: List<JooqJoin>): SelectJoinStep<Record> =
    joins.fold(this) { step, join -> step.applyJoin(join) }

/** Applies [join] as a `LEFT JOIN` or a `JOIN`, see [JooqJoin.left]. */
public fun <R : Record> SelectJoinStep<R>.applyJoin(join: JooqJoin): SelectJoinStep<R> =
    if (join.left) leftJoin(join.table).on(join.condition) else join(join.table).on(join.condition)
