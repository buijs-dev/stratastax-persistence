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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.Sort
import dev.buijs.stratastax.entity.core.api.SortField
import dev.buijs.stratastax.entity.core.api.asc
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.CursorState
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.InExpression
import dev.buijs.stratastax.search.core.api.InOperator
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.CursorElement

/** Builds [SearchQuery]s the way the search layer would parse them. */
internal object Searches {

    fun search(
        filter: FilterExpression? = null,
        sort: List<SortField> = listOf(PlaysetModel.nameField.asc(), PlaysetModel.idField.asc()),
        limit: Int = 50,
        cursor: CursorState? = null,
    ): SearchQuery =
        SearchQuery(
            limit = limit,
            sort = Sort(sort),
            filter = filter?.let(::Filter),
            cursor = cursor,
        )

    fun eq(
        field: EntityField,
        value: String?,
    ): FilterExpression =
        ComparisonExpression(
            field,
            ComparisonOperator.EQUALS,
            value?.let(FilterValue::StringValue) ?: FilterValue.NullValue,
        )

    fun isIn(
        field: EntityField,
        vararg values: String?,
    ): FilterExpression =
        InExpression(
            field,
            InOperator.IN,
            values.map {
                it?.let(FilterValue::StringValue)
                    ?: FilterValue.NullValue
            },
        )

    fun isOut(
        field: EntityField,
        vararg values: String?,
    ): FilterExpression =
        InExpression(
            field,
            InOperator.OUT,
            values.map {
                it?.let(FilterValue::StringValue)
                    ?: FilterValue.NullValue
            },
        )

    fun after(vararg values: CursorElement): CursorState =
        CursorState(values.toList(), hash = "", direction = CursorDirection.AFTER)

    fun before(vararg values: CursorElement): CursorState =
        CursorState(values.toList(), hash = "", direction = CursorDirection.BEFORE)
}
