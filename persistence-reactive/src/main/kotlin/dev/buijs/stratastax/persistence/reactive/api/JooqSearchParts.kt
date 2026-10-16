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
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import org.jooq.SortField as JooqSortField

/**
 * @property condition the caller's filter and the default filters: what a count counts
 * @property seekCondition the position of the search's cursor, if it has one: only the rows of the
 *   requested page lie past it in [orderBy]'s order. Never part of a count.
 */
data class JooqSearchParts(
    val condition: Condition?,
    val orderBy: List<JooqSortField<*>>,
    val selectFields: List<SelectFieldOrAsterisk>,
    val joins: List<JooqJoin>,
    val seekCondition: Condition? = null,
) {

    /** [condition] and [seekCondition]: the rows of one page, before its limit. */
    val pageCondition: Condition
        get() = DSL.and(listOfNotNull(condition, seekCondition))
}
