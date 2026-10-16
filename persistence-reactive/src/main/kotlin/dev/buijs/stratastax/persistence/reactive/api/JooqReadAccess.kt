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

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.persistence.core.spi.ReadAccess
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.reactive.app.isInTransaction
import dev.buijs.stratastax.persistence.reactive.app.selectByIdParts
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.Page
import dev.buijs.stratastax.search.core.api.SearchQuery
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.Table

/**
 * Generic find-side mixin a generated `${Entity}ReadAccess` implements - `findAll`/`findById` are
 * fully implemented here from the entity's own jOOQ binding, reusing exactly the same
 * projection/join/`MULTISET` rendering [JooqSearchQueryTranslator] already provides for search; a
 * generated class only needs to wire the constructor properties below.
 */
interface JooqReadAccess<ID, T> : ReadAccess<ID, T> {

    val dsl: DSLContext
    val table: Table<*>
    val rowMapper: RowMapper<T>
    val fieldMapping: JooqFieldMapping
    val queryExecutor: QueryExecutor
    val queryTranslator: JooqSearchQueryTranslator
    val idField: EntityField

    /** Converts the [ID] this interface is generic over to the id column's value type. */
    fun mapId(id: ID): Any = id as Any

    override suspend fun findAll(search: SearchQuery): Page<T> {
        val parts = queryTranslator.translate(search, fieldMapping)
        val query =
            dsl
                .select(parts.selectFields)
                .from(table)
                .applyJoins(parts.joins)
                .where(parts.pageCondition)
                .orderBy(parts.orderBy)
                .limit(search.limit + 1)

        return pageOf(search, queryExecutor.executeAndGetAll(query), rowMapper)
    }

    override suspend fun findById(id: ID): T? {
        val parts = queryTranslator.translateById(idField, mapId(id), fieldMapping)
        val query = dsl.selectByIdParts(table, parts)

        return queryExecutor.executeAndGetFirst(query)?.let(rowMapper::map)
    }

    /**
     * [JooqSearchQueryTranslator.translate]'s `condition` is already self-contained - every
     * grouped-field condition is wrapped in its own `EXISTS (...)` subquery, independent of the
     * `joins`/`selectFields`/ `orderBy` parts also returned, which only exist for [findAll]'s row
     * projection. So a count query needs none of those - just the condition.
     */
    override suspend fun count(search: SearchQuery): Long {
        val parts = queryTranslator.translate(search, fieldMapping)
        val query = dsl.selectCount().from(table).where(parts.condition)
        return queryExecutor.executeAndGetCount(query)
    }
}

/**
 * Backs every generated `findAll${Projection}(block)` method - shared, entity-agnostic plumbing for
 * a `@DomainEntityProjection`-declared `projection<P>(...)` (see `entity-core`'s
 * `DomainEntityProjection` annotation's own kdoc). Unlike [JooqReadAccess.findAll], [fields]
 * narrows [JooqSearchQueryTranslator]'s select list to just a projection's own declared root
 * fields, and [rowMapper] maps straight into [P] instead of [T] - both supplied by generated code,
 * never computed here, so this stays a plain, reusable wrapper around the same
 * query-building/execution shape [JooqReadAccess.findAll] already uses.
 */
suspend fun <ID, T, P> JooqReadAccess<ID, T>.findAllProjection(
    search: SearchQuery,
    fields: Set<String>,
    rowMapper: RowMapper<P>,
): Page<P> {
    val parts = queryTranslator.translate(search, fieldMapping, fields)
    val query =
        dsl
            .select(parts.selectFields)
            .from(table)
            .applyJoins(parts.joins)
            .where(parts.pageCondition)
            .orderBy(parts.orderBy)
            .limit(search.limit + 1)

    return pageOf(search, queryExecutor.executeAndGetAll(query), rowMapper)
}

/**
 * Like [findAllProjection], but returns every matching row: no `LIMIT`, so [SearchQuery.limit] and
 * any cursor are ignored. Backs every generated `findAll${Projection}List(block)` method - for
 * callers that need the complete set (e.g. "all ids this user owns"), which a page would silently
 * truncate.
 */
suspend fun <ID, T, P> JooqReadAccess<ID, T>.findAllProjectionList(
    search: SearchQuery,
    fields: Set<String>,
    rowMapper: RowMapper<P>,
): List<P> {
    val parts = queryTranslator.translate(search, fieldMapping, fields)
    val query =
        dsl
            .select(parts.selectFields)
            .from(table)
            .applyJoins(parts.joins)
            .where(parts.condition)
            .orderBy(parts.orderBy)

    return queryExecutor.executeAndGetAll(query).map(rowMapper::map)
}

/**
 * One page of [rows], read with one row more than [SearchQuery.limit] to tell whether more follow.
 * A page before a cursor was read in reverse order (see [JooqSearchQueryTranslator]) and is put
 * back in the sort order here.
 */
internal fun <P> pageOf(
    search: SearchQuery,
    rows: List<RowContext>,
    rowMapper: RowMapper<P>,
): Page<P> {
    val hasMore = rows.size > search.limit
    val page = if (hasMore) rows.take(search.limit) else rows
    val ordered =
        if (search.cursor?.direction == CursorDirection.BEFORE) page.asReversed() else page
    return Page(items = ordered.map(rowMapper::map), hasMore = hasMore)
}

/**
 * [JooqReadAccess.findAll] that also locks the returned root rows (`SELECT ... FOR UPDATE OF
 * table`) until the surrounding transaction ends - the building block for "claim the next N
 * unprocessed rows" work queues. With [skipLocked] (the default) rows another transaction already
 * locked are skipped instead of waited for, so concurrent workers each claim a different set.
 *
 * Only the root [JooqReadAccess.table] is locked (`OF table`): Postgres rejects `FOR UPDATE` on the
 * nullable side of the `LEFT JOIN`s an optional 1:1 group adds. Returns at most [SearchQuery.limit]
 * rows and locks exactly those - unlike [JooqReadAccess.findAll], no look-ahead row is fetched to
 * compute `hasMore`, since that row would be locked too.
 *
 * @throws IllegalStateException outside a transaction: in autocommit mode the lock is released the
 *   moment the statement completes, so nothing would actually be claimed.
 */
suspend fun <ID, T> JooqReadAccess<ID, T>.findAllForUpdate(
    search: SearchQuery,
    skipLocked: Boolean = true,
): List<T> {
    check(isInTransaction()) {
        "findAllForUpdate must run inside a transaction (see UnitOfWork.transaction): " +
            "outside one the row locks are released as soon as the query completes"
    }

    val parts = queryTranslator.translate(search, fieldMapping)
    val locking =
        dsl
            .select(parts.selectFields)
            .from(table)
            .applyJoins(parts.joins)
            .where(parts.pageCondition)
            .orderBy(parts.orderBy)
            .limit(search.limit)
            .forUpdate()
            .of(table)
    val query: Query = if (skipLocked) locking.skipLocked() else locking
    val rows = queryExecutor.executeAndGetAll(query)
    val ordered =
        if (search.cursor?.direction == CursorDirection.BEFORE) rows.asReversed() else rows
    return ordered.map(rowMapper::map)
}
