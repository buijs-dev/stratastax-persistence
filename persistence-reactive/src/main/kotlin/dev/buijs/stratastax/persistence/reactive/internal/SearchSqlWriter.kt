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

import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.persistence.reactive.spi.SearchSql
import dev.buijs.stratastax.persistence.reactive.spi.SearchTarget
import dev.buijs.stratastax.persistence.reactive.spi.SqlStatement
import dev.buijs.stratastax.persistence.reactive.spi.SqlType
import dev.buijs.stratastax.search.core.api.AndExpression
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.InExpression
import dev.buijs.stratastax.search.core.api.InOperator
import dev.buijs.stratastax.search.core.api.LikeExpression
import dev.buijs.stratastax.search.core.api.OrExpression
import dev.buijs.stratastax.search.core.api.SearchException
import dev.buijs.stratastax.search.core.api.SearchQuery

/**
 * Renders the runtime parts of one search statement over the generated
 * fragments of [searchSql] (the filter, the sort, the cursor seek, and the limit)
 * collecting their parameters as it goes: one writer per statement.
 */
internal class SearchSqlWriter(
    private val searchSql: SearchSql,
) {

    private val params = mutableListOf<Any?>()

    /** [select] with the given clauses appended, and every parameter bound so far. */
    fun statement(
        select: String,
        conditions: List<String>,
        orderBy: List<String> = emptyList(),
        limit: String? = null,
        lock: String? = null,
    ): SqlStatement {
        val sql =
            buildString {
                append(select.trimEnd())
                if (conditions.isNotEmpty()) {
                    append("\nWHERE ")
                        .append(conditions.joinToString("\n  AND "))
                }

                if (orderBy.isNotEmpty()) {
                    append("\nORDER BY ")
                        .append(orderBy.joinToString(", "))
                }

                limit?.let { append("\nLIMIT ").append(it) }
                lock?.let { append("\n").append(it) }
            }

        return SqlStatement(sql, params.toList())
    }

    /** The caller's filter, [scope] and the default conditions: what a count counts. */
    fun conditions(
        query: SearchQuery,
        scope: FilterExpression?,
    ): List<String> =
        listOfNotNull(query.filter?.let { condition(it.root) }, scope?.let(::condition)) +
            searchSql.defaultConditions

    fun limit(limit: Int): String = bind(limit, SqlType.INTEGER)

    /**
     * The sort of [query], NULL sorting as the largest value (`ASC NULLS LAST`, `DESC NULLS
     * FIRST`). A page before a cursor reads the order backwards.
     */
    fun orderBy(query: SearchQuery): List<String> {
        val backwards = query.cursor?.direction == CursorDirection.BEFORE
        return query.sort.fields.map {
            val column = sortColumn(it.field.name)
            when (it.direction.readAs(backwards)) {
                SortDirection.ASC -> "$column ASC NULLS LAST"
                SortDirection.DESC -> "$column DESC NULLS FIRST"
            }
        }
    }

    /**
     * The rows past [query]'s cursor in the order [orderBy] reads, or `null` without a cursor: one
     * row-value comparison when every sort column is NOT NULL and sorted the same way, else past
     * the first sort value, or equal to it and past the second, and so on.
     *
     * @throws SearchException when the cursor does not belong to the query's sort.
     */
    fun seek(query: SearchQuery): String? {
        val cursor = query.cursor ?: return null
        val sortFields = query.sort.fields
        if (cursor.values.size != sortFields.size) {
            throw SearchException("Cursor does not match the current sort criteria")
        }

        val backwards = cursor.direction == CursorDirection.BEFORE
        val keys =
            sortFields.zip(cursor.values).map { (sortField, element) ->
                val name = sortField.field.name
                val field = searchSql.field(name)
                SeekKey(
                    column = sortColumn(name),
                    type = field.type,
                    direction = sortField.direction.readAs(backwards),
                    value = cursorValueFor(element, field.type.javaType, name),
                    neverNull = field.neverNull,
                )
            }

        return rowValueSeek(keys)
            ?: any(
                keys.indices.map { index ->
                    all(keys.take(index).map(::equal) + beyond(keys[index]))
                },
            )
    }

    private fun condition(expression: FilterExpression): String =
        when (expression) {
            is AndExpression -> {
                all(expression.children.map(::condition))
            }
            is OrExpression -> {
                any(expression.children.map(::condition))
            }
            is ComparisonExpression -> {
                val matchesNull =
                    expression.operator == ComparisonOperator.EQUALS &&
                        expression.value is FilterValue.NullValue
                conditionOn(expression.field.name, matchesNull) { column, type ->
                    comparison(column, type, expression)
                }
            }
            is InExpression -> {
                val matchesNull =
                    expression.operator == InOperator.IN &&
                        expression.values.any { it is FilterValue.NullValue }

                conditionOn(expression.field.name, matchesNull) { column, type ->
                    membership(column, type, expression)
                }
            }
            is LikeExpression -> {
                conditionOn(expression.field.name, matchesNull = false) { column, type ->
                    val operator = if (expression.ignoreCase) "ILIKE" else "LIKE"
                    "$column $operator ${bind(expression.pattern, type)} ESCAPE '\\'"
                }
            }
        }

    /**
     * [build]'s condition on field [name]'s column, inside the field's `EXISTS` when it belongs
     * to a relation. A condition that [matchesNull] also matches rows without the related row of
     * a one-to-one chain, where the field reads as NULL.
     */
    private fun conditionOn(
        name: String,
        matchesNull: Boolean,
        build: (column: String, type: SqlType) -> String,
    ): String {
        val field = searchSql.field(name)
        return when (val target = field.target) {
            is SearchTarget.Column -> {
                build(target.column, field.type)
            }
            is SearchTarget.Related -> {
                val matching =
                    target.exists.replace(
                        SearchTarget.Related.CONDITION,
                        build(target.column, field.type),
                    )
                val absent = target.absent
                if (matchesNull && absent != null) any(listOf(matching, absent)) else matching
            }
        }
    }

    private fun comparison(
        column: String,
        type: SqlType,
        expression: ComparisonExpression,
    ): String {
        val value = filterValueFor(expression.value, type.javaType)
        return when (expression.operator) {
            ComparisonOperator.EQUALS -> {
                if (value == null) "$column IS NULL" else "$column = ${bind(value, type)}"
            }
            ComparisonOperator.NOT_EQUALS -> {
                if (value == null) "$column IS NOT NULL" else "$column <> ${bind(value, type)}"
            }
            ComparisonOperator.GREATER_THAN -> {
                "$column > ${bind(nonNull(value, expression), type)}"
            }
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> {
                "$column >= ${bind(nonNull(value, expression), type)}"
            }
            ComparisonOperator.LESS_THAN -> {
                "$column < ${bind(nonNull(value, expression), type)}"
            }
            ComparisonOperator.LESS_THAN_OR_EQUAL -> {
                "$column <= ${bind(nonNull(value, expression), type)}"
            }
            ComparisonOperator.LIKE, ComparisonOperator.ILIKE -> {
                throw SearchException(
                    "${expression.operator} on '${expression.field.name}' must be a LikeExpression",
                )
            }
        }
    }

    private fun nonNull(
        value: Any?,
        expression: ComparisonExpression,
    ): Any =
        value ?: throw SearchException(
            "Operator '${expression.operator}' on field '${expression.field.name}' does not accept null",
        )

    /**
     * `IN` matches NULL when one of the values is null (SQL's own `IN` never does); `OUT` with a
     * null among the values does not match NULL either. Several values of an array-bindable type
     * are bound as one array, so the text does not depend on the number of values.
     */
    private fun membership(
        column: String,
        type: SqlType,
        expression: InExpression,
    ): String {
        val values = expression.values.map { filterValueFor(it, type.javaType) }

        val nonNull = values.filterNotNull().distinct()
        val hasNull = values.any { it == null }

        return when (expression.operator) {
            InOperator.IN -> {
                val matchesValue =
                    when {
                        nonNull.isEmpty() -> null
                        nonNull.size == 1 -> "$column = ${bind(nonNull.single(), type)}"
                        type.bindsAsArray -> "$column = ANY(${bindArray(nonNull, type)})"
                        else -> "$column IN (${nonNull.joinToString { bind(it, type) }})"
                    }

                any(listOfNotNull(matchesValue, if (hasNull) "$column IS NULL" else null))
            }
            InOperator.OUT -> {
                val notAValue =
                    when {
                        nonNull.isEmpty() -> null
                        nonNull.size > 1 && type.bindsAsArray -> "$column <> ALL(${bindArray(
                            nonNull,
                            type,
                        )})"
                        else -> "$column NOT IN (${nonNull.joinToString { bind(it, type) }})"
                    }

                all(listOfNotNull(notAValue, if (hasNull) "$column IS NOT NULL" else null))
            }
        }
    }

    private fun sortColumn(name: String): String =
        searchSql.field(name).sortColumn
            ?: throw SearchException(
                "Field '$name' belongs to a one-to-many relation and cannot be sorted on",
            )

    private fun rowValueSeek(keys: List<SeekKey>): String? {
        val direction = keys.first().direction
        val eligible = keys.all { it.direction == direction && it.value != null && it.neverNull }

        if (!eligible || keys.size < 2) {
            return null
        }

        val columns = keys.joinToString(", ", "(", ")") { it.column }
        val values = keys.joinToString(", ", "(", ")") { bind(it.value, it.type) }
        return if (direction == SortDirection.ASC) {
            "$columns > $values"
        } else {
            "$columns < $values"
        }
    }

    private fun equal(key: SeekKey): String =
        if (key.value == null) {
            "${key.column} IS NULL"
        } else {
            "${key.column} = ${bind(key.value, key.type)}"
        }

    /** NULL sorts as the largest value, see [orderBy]. */
    private fun beyond(key: SeekKey): String =
        when (key.direction) {
            SortDirection.ASC -> {
                if (key.value == null) {
                    "FALSE"
                } else {
                    "(${key.column} > ${bind(key.value, key.type)} OR ${key.column} IS NULL)"
                }
            }
            SortDirection.DESC -> {
                if (key.value == null) {
                    "${key.column} IS NOT NULL"
                } else {
                    "${key.column} < ${bind(key.value, key.type)}"
                }
            }
        }

    /** A placeholder for [value], cast to [type]: `$3::uuid`. */
    private fun bind(
        value: Any?,
        type: SqlType,
    ): String {
        params += value
        return $$"$$${params.size}::$${type.sqlName}"
    }

    /** One array placeholder for [values]: `$3::uuid[]`, bound as an array of [type]'s Java type. */
    private fun bindArray(
        values: List<Any>,
        type: SqlType,
    ): String {
        val array =
            java.lang.reflect.Array
                .newInstance(type.javaType, values.size)
        values.forEachIndexed { index, value ->
            java.lang.reflect.Array
                .set(array, index, value)
        }

        params += array
        return $$"$$${params.size}::$${type.sqlName}[]"
    }

    private class SeekKey(
        val column: String,
        val type: SqlType,
        val direction: SortDirection,
        val value: Any?,
        val neverNull: Boolean,
    )
}

/** [conditions] combined with `AND`: one as is, several in parentheses, none as `TRUE`. */
private fun all(conditions: List<String>): String =
    when (conditions.size) {
        0 -> "TRUE"
        1 -> conditions.single()
        else -> conditions.joinToString(" AND ", "(", ")")
    }

/** [conditions] combined with `OR`: one as is, several in parentheses, none as `FALSE`. */
private fun any(conditions: List<String>): String =
    when (conditions.size) {
        0 -> "FALSE"
        1 -> conditions.single()
        else -> conditions.joinToString(" OR ", "(", ")")
    }

private fun SortDirection.readAs(backwards: Boolean): SortDirection =
    when {
        !backwards -> this
        this == SortDirection.ASC -> SortDirection.DESC
        else -> SortDirection.ASC
    }
