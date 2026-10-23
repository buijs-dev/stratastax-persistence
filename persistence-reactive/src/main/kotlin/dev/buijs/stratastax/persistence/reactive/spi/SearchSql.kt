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

import dev.buijs.stratastax.entity.core.spi.FieldMapping
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.reactive.internal.SearchSqlWriter
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.Page
import dev.buijs.stratastax.search.core.api.SearchQuery
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * How one entity is searched in SQL: the literal fragments generated code supplies, from which a
 * [SearchQuery] is rendered into one statement (see [pageQuery], [listQuery], [lockQuery] and
 * [countQuery]). Only the filter, the sort, the cursor and the limit are rendered at runtime;
 * everything else is the generated text as is.
 *
 * @property dialect the dialect the fragments are written for, and the runtime parts rendered in.
 * @property select the read projection without a condition: `SELECT <columns> FROM <table> <joins>`.
 * @property countSelect `SELECT count(*) FROM <table>`, without joins.
 * @property fields every searchable field by name.
 * @property defaultConditions conditions every read applies, e.g. `playset.deleted_at IS NULL`.
 * @property lockClause the row-locking clause of [lockQuery], e.g. `FOR UPDATE OF playset`.
 */
public class SearchSql(
    public val dialect: PersistenceDialect,
    public val select: String,
    public val countSelect: String,
    override val fields: Map<String, SearchField>,
    public val defaultConditions: List<String> = emptyList(),
    public val lockClause: String? = null,
) : FieldMapping<SearchField>

/**
 * One searchable field.
 *
 * @property type the SQL type of its column: what filter and cursor values are converted to.
 * @property target where a condition on the field is written.
 * @property sortColumn the column a sort on the field orders by (a root column, or a joined
 *   one-to-one column of [SearchSql.select]) or `null` for a field of a one-to-many relation,
 *   which has no single value to sort on.
 * @property neverNull whether the field never reads as NULL (a NOT NULL column of the root table),
 *   which lets a cursor seek be one row-value comparison.
 */
public class SearchField(
    public val type: SqlType,
    public val target: SearchTarget,
    public val sortColumn: String? = null,
    public val neverNull: Boolean = false,
)

/** Where a condition on a [SearchField] is written. */
public sealed interface SearchTarget {

    /** A [column] of the root table, e.g. `playset.name`. */
    public data class Column(
        val column: String,
    ) : SearchTarget

    /**
     * A [column] of a related table, reached through [exists]: an `EXISTS (...)` subquery along the
     * relation chain, with `{condition}` where the condition on [column] goes, so a filter on a
     * one-to-many relation matches a root row once, however many children match.
     *
     * [absent] (`NOT EXISTS (...)`, only for a chain of one-to-one relations) matches the rows
     * without the related row. A condition that matches NULL matches those rows as well, as the
     * field reads as NULL there in the read projection.
     */
    public data class Related(
        val exists: String,
        val column: String,
        val absent: String? = null,
    ) : SearchTarget {

        init {
            require(
                CONDITION in exists,
            ) { "'exists' must hold the placeholder $CONDITION: $exists" }
        }

        public companion object {

            /** Where the condition goes in [exists]. */
            public const val CONDITION: String = "{condition}"
        }
    }
}

/**
 * The SQL type of a searchable column: the Java type filter and cursor values are converted to
 * ([javaType]), and the name a dialect writes it with ([sqlName]), e.g. in a cast `$1::uuid`.
 *
 * The constants are the common types; a dialect with other column types declares its own.
 */
public data class SqlType(
    val sqlName: String,
    val javaType: Class<*>,
) {
    public companion object {
        public val UUID: SqlType = SqlType("uuid", java.util.UUID::class.java)
        public val TEXT: SqlType = SqlType("text", String::class.java)
        public val VARCHAR: SqlType = SqlType("varchar", String::class.java)
        public val SMALLINT: SqlType = SqlType("smallint", Short::class.javaObjectType)
        public val INTEGER: SqlType = SqlType("integer", Int::class.javaObjectType)
        public val BIGINT: SqlType = SqlType("bigint", Long::class.javaObjectType)
        public val NUMERIC: SqlType = SqlType("numeric", BigDecimal::class.java)
        public val BOOLEAN: SqlType = SqlType("boolean", Boolean::class.javaObjectType)
        public val DATE: SqlType = SqlType("date", LocalDate::class.java)
        public val TIMESTAMP: SqlType = SqlType("timestamp", LocalDateTime::class.java)
        public val TIMESTAMPTZ: SqlType = SqlType("timestamptz", OffsetDateTime::class.java)
        public val JSONB: SqlType = SqlType("jsonb", Map::class.java)
    }
}

/** SQL text and the parameters of its placeholders, in order: the first is `params[0]`. */
public data class SqlStatement(
    val sql: String,
    val params: List<Any?> = emptyList(),
)

/**
 * One page of [query]: the rows of [select] (by default the full read projection) matching its
 * filter, [scope] and the default conditions, past its cursor, in its sort order, and one row more
 * than its limit, which [readPage] uses to tell whether more rows follow.
 *
 * A [scope] restricts like the filter (what the caller may see) but is not part of [query], so
 * the cursors of the page only carry the caller's own sort and filter.
 *
 * @throws dev.buijs.stratastax.search.core.api.SearchException when [query] does not fit the
 *   fields (a sort on a one-to-many field, a cursor for another sort, a value of the wrong type).
 * @throws dev.buijs.stratastax.entity.core.spi.UnknownFieldException for a field that is not in
 *   [SearchSql.fields].
 */
public fun SearchSql.pageQuery(
    query: SearchQuery,
    scope: FilterExpression? = null,
    select: String = this.select,
): SqlStatement =
    SearchSqlWriter(this).run {
        statement(
            select,
            conditions(query, scope) + listOfNotNull(seek(query)),
            orderBy(query),
            limit(query.limit + 1),
        )
    }

/**
 * Every row of [select] matching [query]'s filter, [scope] and the default conditions, in its sort
 * order: its limit and cursor are ignored. For callers that need the complete set; mind the size.
 */
public fun SearchSql.listQuery(
    query: SearchQuery,
    scope: FilterExpression? = null,
    select: String = this.select,
): SqlStatement =
    SearchSqlWriter(this).run { statement(select, conditions(query, scope), orderBy(query)) }

/**
 * Like [pageQuery], but locking the returned root rows with [SearchSql.lockClause] until the
 * surrounding transaction ends (skipping locked rows with [skipLocked]) and without the look-ahead row
 * (it would be locked too).
 *
 * @throws IllegalStateException when the entity has no [SearchSql.lockClause].
 */
public fun SearchSql.lockQuery(
    query: SearchQuery,
    skipLocked: Boolean = true,
): SqlStatement {
    val lock = checkNotNull(lockClause) { "This entity has no lock clause" }

    return SearchSqlWriter(this).run {
        statement(
            select,
            conditions(query, scope = null) + listOfNotNull(seek(query)),
            orderBy(query),
            limit(query.limit),
            if (skipLocked) dialect.search.skipLocked(lock) else lock,
        )
    }
}

/** Counts the rows matching [query]'s filter, [scope] and the default conditions. */
public fun SearchSql.countQuery(
    query: SearchQuery,
    scope: FilterExpression? = null,
): SqlStatement = SearchSqlWriter(this).run { statement(countSelect, conditions(query, scope)) }

/**
 * Runs [statement] (a [pageQuery] of [search]) and returns its page: the look-ahead row tells
 * whether more rows follow, and a page before a cursor (read backwards) is put back in sort order.
 */
public suspend fun <T> QueryExecutor.readPage(
    statement: SqlStatement,
    search: SearchQuery,
    rowMapper: RowMapper<T>,
): Page<T> {
    val rows = query(statement.sql, statement.params)
    val hasMore = rows.size > search.limit
    val page = if (hasMore) rows.take(search.limit) else rows
    return Page(items = inSortOrder(search, page).map(rowMapper::map), hasMore = hasMore)
}

/** Runs [statement] (a [listQuery] or [lockQuery] of [search]) and maps its rows in sort order. */
public suspend fun <T> QueryExecutor.readAll(
    statement: SqlStatement,
    search: SearchQuery,
    rowMapper: RowMapper<T>,
): List<T> = inSortOrder(search, query(statement.sql, statement.params)).map(rowMapper::map)

private fun inSortOrder(
    search: SearchQuery,
    rows: List<RowContext>,
): List<RowContext> =
    if (search.cursor?.direction == CursorDirection.BEFORE) {
        rows.asReversed()
    } else {
        rows
    }
